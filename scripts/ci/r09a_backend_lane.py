#!/usr/bin/env python3
"""R09A executable Go backend test/coverage/fuzz evidence (hosted-only).

Do not infer root/device qualification from Go test results. This lane runs
on the checked out SHA, preserves real failure output, and emits observed
fuzz and coverage receipts for downstream verification.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

PACKAGES = {
    'config': './internal/config',
    'control': './internal/control',
    'eventbridge': './internal/eventbridge',
}
FUZZ = {
    'config': ['FuzzR09AEventName','FuzzR09ARequiredPathTypes'],
    'control': ['FuzzR09ARequestID'],
    'eventbridge': ['FuzzR09AHexDigest','FuzzR09ACanonicalJSON'],
}
SCHEMA='xbs-r09a-backend-evidence/v1'

class Refused(RuntimeError):pass

def need(ok, reason):
    if not ok:raise Refused(reason)

def cmd(args, *, cwd, timeout=300):
    try:r=subprocess.run(args, cwd=cwd, text=True, capture_output=True, errors='replace', timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as exc:raise Refused('COMMAND_UNAVAILABLE_OR_TIMEOUT '+repr(args)) from exc
    if r.returncode:
        evidence='\n'.join((r.stdout+'\n'+r.stderr).splitlines()[-25:])[-5500:]
        raise Refused('COMMAND_FAILURE '+repr(args)+' rc='+str(r.returncode)+' '+evidence)
    return r.stdout

def parse_events(raw,expected_prefix='R09A'):
    passed=set();skipped=set();failed=set();packages=set()
    for i,line in enumerate(raw.splitlines(),1):
        if not line.strip():continue
        try:row=json.loads(line)
        except json.JSONDecodeError as exc:raise Refused('GO_JSON_BAD_EVENT_LINE_'+str(i)) from exc
        need(isinstance(row,dict),'GO_JSON_NONOBJECT_'+str(i))
        action=row.get('Action');pkg=row.get('Package');test=row.get('Test')
        if action=='fail':failed.add((str(pkg),str(test)))
        if action=='skip' and test:skipped.add((str(pkg),str(test)))
        if action=='pass' and pkg and test:
            key=(str(pkg),str(test))
            need(key not in passed,'DUPLICATE_GO_TEST '+str(key))
            passed.add(key)
        if action=='pass' and pkg and not test:packages.add(str(pkg))
    need(not failed and not skipped,'GO_TEST_FAILED_OR_SKIPPED failures='+repr(sorted(failed))+' skips='+repr(sorted(skipped)))
    need(bool(passed) and len(packages)>=3,'GO_BACKEND_EMPTY_OR_PARTIAL_SEED_RECEIPTS')
    need(all(p.startswith('github.com/Mikeyphw/xbsctl/internal/') for p,_ in passed),'GO_BACKEND_PACKAGE_IDENTITY_DRIFT')
    required={
        'config': {'TestR09AConfigTypeNegatives', *FUZZ['config']},
        'control': {'TestR09AConcurrentQueueIDs', *FUZZ['control']},
        'eventbridge': {'TestR09ADigestMalformedInputs', *FUZZ['eventbridge']},
    }
    for name,needed in required.items():
        nameid='/internal/'+name
        matched={t.split('/',1)[0] for p,t in passed if p.endswith(nameid) and t.startswith(('Test'+expected_prefix,'Fuzz'+expected_prefix))}
        need(needed<=matched,'GO_BACKEND_MISSING_SEEDS '+name+' '+repr(sorted(needed-matched)))
    need({p for p,_ in passed}<=packages,'GO_BACKEND_TEST_WITHOUT_PACKAGE_PASS')
    return {'test_cases':len(passed),'passed_packages':len(packages),
            'passed_case_ids':sorted(p+'::'+t for p,t in passed),
            'failed_cases':0,'unexpected_skipped_cases':0}

def parse_fuzz(raw,expected):
    # Go emits the real execution counts at the end of fuzzing, not only 'PASS'.
    execution=[int(n) for n in re.findall(r'(?m)^fuzz:\s+elapsed:.*?\bexecs:\s*(\d+)',raw)]
    need('PASS' in raw and re.search(r'(?m)^ok\s+github\.com/Mikeyphw/xbsctl/internal/',raw) is not None,
         'FUZZ_RUN_DID_NOT_PASS '+expected)
    need(bool(execution) and max(execution)>0,'FUZZ_ZERO_EXECUTIONS '+expected)
    return max(execution)

def parse_coverage(raw):
    totals=re.findall(r'(?m)^total:\s+\(statements\)\s+([0-9]+(?:\.[0-9]+)?)%',raw)
    need(len(totals)==1,'COVERAGE_TOTAL_MISSING_OR_AMBIGUOUS')
    percentage=float(totals[0]);need(0<percentage<=100,'COVERAGE_ZERO_OR_INVALID')
    return percentage

def main_hosted(repo:Path):
    need(os.environ.get('GITHUB_ACTIONS')=='true','NOT_GITHUB_ACTIONS')
    need(os.environ.get('XBS_ROOT_IT_ACK') is None,'ROOT_INTEGRATION_ACK_FORBIDDEN')
    need(os.environ.get('GITHUB_EVENT_NAME') in ('pull_request','push','workflow_dispatch'),
         'GITHUB_EVENT_NOT_ALLOWED')
    if hasattr(os,'geteuid'):need(os.geteuid()!=0,'ROOTED_HOSTED_JOB_FORBIDDEN')
    sha=cmd(['git','rev-parse','HEAD'],cwd=repo).strip()
    need(re.fullmatch('[0-9a-f]{40}',sha) is not None,'SOURCE_SHA_INVALID')
    need(sha==os.environ.get('XBS_EXPECTED_HEAD_SHA'),'CHECKOUT_HEAD_SHA_DRIFT')
    run_id=os.environ.get('GITHUB_RUN_ID','')
    attempt=os.environ.get('GITHUB_RUN_ATTEMPT','')
    need(run_id.isdecimal() and attempt.isdecimal() and int(run_id)>0 and int(attempt)>0,
         'GITHUB_RUN_IDENTITY_NOT_OBSERVED')
    output=repo/'dist'
    need(output.is_dir() and not output.is_symlink() or not output.exists(),'UNSAFE_DIST_DIRECTORY')
    output.mkdir(parents=True,exist_ok=True)
    receipt=output/'r09a-backend-qualification.json'
    profile=output/'r09a-backend.coverprofile'
    need(not receipt.exists() and not profile.exists(),'STALE_R09A_EVIDENCE')
    seed_out=cmd(['go','test','-json','-count=1','-run','^(TestR09A|FuzzR09A)',*PACKAGES.values()],cwd=repo,timeout=240)
    result=parse_events(seed_out)
    # Repetition is a real fresh-run test, not an optimistic retry after failure.
    repeated=cmd(['go','test','-json','-count=3','-run','^TestR09A',*PACKAGES.values()],cwd=repo,timeout=240)
    repeated_events=parse_repeat_events(repeated)
    fuzz_counts={}
    for package,tests in FUZZ.items():
        for fn in tests:
            real=cmd(['go','test','-run','^$','-fuzz','^'+fn+'$', '-fuzztime=2s','-parallel=2',PACKAGES[package]],
                     cwd=repo,timeout=180)
            fuzz_counts[package+'/'+fn]=parse_fuzz(real,package+'/'+fn)
    cmd(['go','test','-covermode=atomic','-coverprofile='+str(profile),'-count=1',
         '-run','^(TestR09A|FuzzR09A)',*PACKAGES.values()],cwd=repo,timeout=300)
    coverage_raw=cmd(['go','tool','cover','-func='+str(profile)],cwd=repo,timeout=120)
    pct=parse_coverage(coverage_raw)
    digest=hashlib.sha256(profile.read_bytes()).hexdigest()
    result.update({'schema':SCHEMA,'xbs_source_sha':sha,'github_run_id':run_id,
                   'github_run_attempt':attempt,'fuzz_executions':fuzz_counts,
                   'fuzz_targets_executed':len(fuzz_counts),
                   'repeated_test_cases':repeated_events,
                   'coverage_total_statements_pct':pct,'coverage_profile_sha256':digest,
                   'physical_root_qualified':False,'device_mutated':False,'release_qualified':False})
    need(result['fuzz_targets_executed']==5 and result['repeated_test_cases']>=6,'R09A_BACKEND_REQUIRED_TARGET_INVENTORY_DRIFT')
    receipt.write_text(json.dumps(result,indent=2,sort_keys=True)+'\n')
    print('XBS_R09A_ACTUAL_BACKEND_PASS cases='+str(result['test_cases'])+
          ' repeated='+str(repeated_events)+' fuzz_targets=5'+
          ' fuzz_execs='+str(sum(fuzz_counts.values()))+' coverage_pct='+str(pct),flush=True)
    print('XBS_R09A_SOURCE_BOUND_EVIDENCE '+str(receipt),flush=True)

def parse_repeat_events(raw):
    seen=0;packages=set();fails=[];skips=[]
    for i,l in enumerate(raw.splitlines(),1):
        if not l.strip():continue
        try:r=json.loads(l)
        except ValueError as e:raise Refused('REPEAT_TEST_JSON_INVALID '+str(i)) from e
        if r.get('Action')=='pass' and r.get('Test') and str(r['Test']).startswith('TestR09A'):seen+=1
        if r.get('Action')=='pass' and r.get('Package') and not r.get('Test'):packages.add(r['Package'])
        if r.get('Action') in ('fail','skip'):fails.append(str(r.get('Test') or r.get('Package')))
    need(not fails,'REPEAT_TEST_FAILED_OR_SKIPPED '+repr(fails))
    need(len(packages)>=3 and seen>=3*3,'REPEAT_TEST_ZERO_OR_PARTIAL cases='+str(seen))
    return seen

if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--hosted',action='store_true')
    p.add_argument('--repo',type=Path,default=Path.cwd())
    args=p.parse_args()
    try:
        need(args.hosted,'HOSTED_ONLY_LANE')
        main_hosted(args.repo.resolve())
    except Refused as exc:
        print('XBS_R09A_REFUSED '+str(exc),file=sys.stderr)
        raise SystemExit(2)
