#!/usr/bin/env python3
"""R09B hosted-only Go fault matrix: actual source-bound test, repeat and race receipts."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

SCHEMA='xbs-r09b-backend-fault-matrix/v1'
PACKAGE_TESTS={
  'config':{'TestR09BHeaderTypeFaultMatrix','TestR09BEventGrammarFaultMatrix'},
  'control':{'TestR09BInterruptedClaimRecovery','TestR09BPublishedAckNonreplace','TestR09BUnsafeQueueRequests'},
  'tuning':{'TestR09BWALFailureMatrix','TestR09BRollbackFailureRecovery','TestR09BRestartSnapshotRestore','TestR09BDryRunNoMutation'},
  'module':{'TestR09BMalformedZipMatrix','TestR09BInvalidZipContainer'},
}
PACKAGES=['./internal/'+p for p in PACKAGE_TESTS]
PREFIX='github.com/Mikeyphw/xbsctl/internal/'
class Refused(RuntimeError):pass
def need(ok,reason):
    if not ok:raise Refused(reason)
def invoke(cmd,repo,timeout=300):
    try:r=subprocess.run(cmd,cwd=repo,text=True,capture_output=True,errors='replace',timeout=timeout)
    except (OSError,subprocess.TimeoutExpired) as e:raise Refused('COMMAND_ERROR_OR_TIMEOUT '+repr(cmd[:5])) from e
    if r.returncode:
        msg='\n'.join((r.stdout+'\n'+r.stderr).splitlines()[-35:])[-6500:]
        raise Refused('COMMAND_FAILED '+repr(cmd[:5])+' exit='+str(r.returncode)+'\n'+msg)
    return r.stdout

def parse_test_events(raw, repetitions=1):
    """Require *every named root test* to pass once per count; forbid skip/fail events.

    Subtests add evidence but cannot replace a missing root test. Package-level
    pass must be present for every expected package and every repetition.
    """
    roots={};all_cases=set();packages={};failed=[];skipped=[]
    for line_number,line in enumerate(raw.splitlines(),1):
        if not line.strip():continue
        try:v=json.loads(line)
        except ValueError as e:raise Refused('GO_TEST_JSON_INVALID_LINE '+str(line_number)) from e
        need(isinstance(v,dict),'GO_TEST_JSON_NONOBJECT_LINE '+str(line_number))
        action=v.get('Action');pkg=v.get('Package');case=v.get('Test')
        if action in ('fail','skip'):
            (failed if action=='fail' else skipped).append(str(pkg)+'::'+str(case))
        if action=='pass' and isinstance(pkg,str):
            if isinstance(case,str) and case:
                all_cases.add(pkg+'::'+case)
                if '/' not in case:roots[(pkg,case)]=roots.get((pkg,case),0)+1
            elif not case:packages[pkg]=packages.get(pkg,0)+1
    need(not failed and not skipped,'GO_TEST_FAILED_OR_SKIPPED '+repr((failed,skipped)))
    required={(PREFIX+name,case) for name,cases in PACKAGE_TESTS.items() for case in cases}
    missing=required-set(roots)
    need(not missing,'GO_R09B_REQUIRED_TEST_MISSING '+repr(sorted(missing)))
    wrong={key:n for key,n in roots.items() if key not in required or n!=repetitions}
    need(not wrong,'GO_R09B_ROOT_COUNT_MISMATCH '+repr(wrong))
    need(set(packages)=={PREFIX+key for key in PACKAGE_TESTS},'GO_R09B_PACKAGE_INVENTORY_MISMATCH '+repr(sorted(packages)))
    need(all(count==1 for count in packages.values()),'GO_R09B_PACKAGE_PASS_DUPLICATE')
    return {'required_root_tests':len(required),
            'passed_root_test_events':sum(roots.values()),
            'all_passed_unique_cases':len(all_cases),
            'required_test_ids':sorted(p+'::'+t for p,t in required),
            'passed_packages':len(packages),'failed_cases':0,'skipped_cases':0}

def validate_receipt(data,sha,run_id,attempt):
    need(isinstance(data,dict) and data.get('schema')==SCHEMA,'R09B_SCHEMA_MISMATCH')
    need(data.get('xbs_source_sha')==sha and data.get('github_run_id')==str(run_id) and data.get('github_run_attempt')==str(attempt),'R09B_SOURCE_RUN_ATTEMPT_DRIFT')
    expected=sorted(PREFIX+p+'::'+name for p,names in PACKAGE_TESTS.items() for name in names)
    need(data.get('required_test_ids')==expected,'R09B_TEST_ID_INVENTORY_WRONG')
    need(data.get('required_root_tests')==len(expected),'R09B_EXPECTED_ROOT_TEST_COUNT_WRONG')
    need(data.get('passed_root_test_events')==len(expected) and data.get('repeat_passed_root_events')==3*len(expected) and data.get('race_passed_root_events')==len(expected),'R09B_REAL_TEST_COUNTS_MISSING')
    need(data.get('failed_cases')==data.get('skipped_cases')==0,'R09B_FAILED_OR_SKIPPED')
    need(data.get('passed_packages')==4 and data.get('race_packages')==4,'R09B_PACKAGE_MATRIX_INCOMPLETE')
    need(data.get('fault_domains')==sorted(PACKAGE_TESTS),'R09B_RISK_DOMAINS_WRONG')
    need(all(data.get(k) is False for k in ('root_executed','physical_root_qualified','device_mutated','release_qualified')),'R09B_FALSE_ROOT_OR_RELEASE_ASSERTION')
    return {'tests':len(expected),'repeat_events':data['repeat_passed_root_events'],
            'race_events':data['race_passed_root_events'],'domains':data['fault_domains']}

def hosted(repo):
    need(os.environ.get('GITHUB_ACTIONS')=='true' and os.environ.get('GITHUB_EVENT_NAME') in ('pull_request','push','workflow_dispatch'),'NOT_TRUSTED_HOSTED_ACTIONS')
    need(os.environ.get('XBS_ROOT_IT_ACK') is None,'ROOT_INTEGRATION_ACK_FORBIDDEN')
    if hasattr(os,'geteuid'):need(os.geteuid()!=0,'HOSTED_ROOT_FORBIDDEN')
    sha=invoke(['git','rev-parse','HEAD'],repo).strip()
    need(re.fullmatch('[0-9a-f]{40}',sha)!=None and sha==os.environ.get('XBS_EXPECTED_HEAD_SHA'),'CHECKOUT_SHA_DRIFT')
    run_id=os.environ.get('GITHUB_RUN_ID','');attempt=os.environ.get('GITHUB_RUN_ATTEMPT','')
    need(run_id.isdecimal() and attempt.isdecimal() and int(run_id)>0 and int(attempt)>0,'RUN_IDENTITY_MISSING')
    folder=repo/'dist';need(not folder.is_symlink(),'UNSAFE_OUTPUT_DIRECTORY')
    folder.mkdir(parents=True,exist_ok=True)
    receipt=folder/'r09b-backend-matrix.json';need(not receipt.exists(),'STALE_R09B_RECEIPT')
    baseline=parse_test_events(invoke(['go','test','-json','-count=1','-run','^TestR09B',*PACKAGES],repo,timeout=420))
    repeated=parse_test_events(invoke(['go','test','-json','-count=3','-run','^TestR09B',*PACKAGES],repo,timeout=480),repetitions=3)
    raced=parse_test_events(invoke(['go','test','-race','-json','-count=1','-run','^TestR09B',*PACKAGES],repo,timeout=540))
    payload={**baseline,'schema':SCHEMA,'xbs_source_sha':sha,'github_run_id':run_id,
        'github_run_attempt':attempt,'repeat_passed_root_events':repeated['passed_root_test_events'],
        'race_passed_root_events':raced['passed_root_test_events'],'race_packages':raced['passed_packages'],
        'fault_domains':sorted(PACKAGE_TESTS), 'root_executed':False,'physical_root_qualified':False,
        'device_mutated':False,'release_qualified':False}
    validate_receipt(payload,sha,run_id,attempt)
    receipt.write_text(json.dumps(payload,sort_keys=True,indent=2)+'\n')
    print('XBS_R09B_BACKEND_MATRIX_PASS tests='+str(payload['required_root_tests'])+
       ' repeat='+str(payload['repeat_passed_root_events'])+' race='+str(payload['race_passed_root_events'])+
       ' domains=4',flush=True)
    print('XBS_R09B_SOURCE_BOUND_RECEIPT '+str(receipt)+' sha256='+hashlib.sha256(receipt.read_bytes()).hexdigest(),flush=True)

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--hosted',action='store_true');parser.add_argument('--repo',type=Path,default=Path.cwd())
    args=parser.parse_args()
    try:
        need(args.hosted,'HOSTED_ONLY')
        hosted(args.repo.resolve())
    except Refused as e:
        print('XBS_R09B_REFUSED '+str(e),file=sys.stderr)
        raise SystemExit(2)
if __name__=='__main__':main()
