#!/usr/bin/env python3
"""R09A independent real GitHub source-bound fuzz/coverage artifact audit."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile

REPO='Mikeyphw/xbsctl'
BRANCH='feature/xbs-r09a-backend-fuzz-evidence-20261114'
WORKFLOW='Devtool XBS Go and Android candidates'
STEP='R09A bounded backend fuzz, repeat and coverage'
SCHEMA='xbs-r09a-backend-evidence/v1'
class Refused(RuntimeError):pass

def need(ok,msg):
    if not ok:raise Refused(msg)

def gh(*args,timeout=150):
    try:p=subprocess.run(['gh',*args],capture_output=True,text=True,errors='replace',timeout=timeout)
    except (OSError,subprocess.TimeoutExpired) as e:raise Refused('GH_COMMAND_UNAVAILABLE_OR_TIMEOUT '+str(args[:3])) from e
    if p.returncode:raise Refused('GH_COMMAND_FAILED '+str(args[:4])+' rc='+str(p.returncode)+' '+p.stderr[-600:])
    return p.stdout

def check_receipt(data,sha,run,attempt,profile):
    need(isinstance(data,dict) and data.get('schema')==SCHEMA,'R09A_SCHEMA_WRONG')
    need(data.get('xbs_source_sha')==sha,'R09A_SHA_WRONG')
    need(data.get('github_run_id')==str(run) and data.get('github_run_attempt')==str(attempt),'R09A_RUN_ATTEMPT_WRONG')
    need(type(data.get('test_cases')) is int and data['test_cases']>=8,'R09A_TEST_CASES_MISSING')
    need(type(data.get('repeated_test_cases')) is int and data['repeated_test_cases']>=9,'R09A_REPEAT_MISSING')
    need(data.get('failed_cases')==0 and data.get('unexpected_skipped_cases')==0,'R09A_FAILED_OR_SKIPPED_CASE')
    need(data.get('fuzz_targets_executed')==5 and isinstance(data.get('fuzz_executions'),dict) and len(data['fuzz_executions'])==5,'R09A_FUZZ_TARGET_COUNT_WRONG')
    need(all(type(v) is int and v>0 for v in data['fuzz_executions'].values()),'R09A_FUZZ_EXECUTIONS_ZERO')
    pct=data.get('coverage_total_statements_pct')
    need(type(pct) in (float,int) and 0<pct<=100,'R09A_COVERAGE_INVALID')
    need(data.get('coverage_profile_sha256')==hashlib.sha256(profile).hexdigest(),'R09A_COVERAGE_PROFILE_HASH_MISMATCH')
    need(data.get('physical_root_qualified') is False and data.get('device_mutated') is False and data.get('release_qualified') is False,'R09A_FALSE_PHYSICAL_OR_RELEASE_CLAIM')
    return {'tests':data['test_cases'],'repeated_cases':data['repeated_test_cases'],'fuzz_targets':5,'fuzz_executions':sum(data['fuzz_executions'].values()),'coverage_pct':pct}

def verify(repo,sha,pr):
    need(re.fullmatch('[0-9a-f]{40}',sha)!=None,'SHA_INVALID')
    # Bound the run to the precise PR source, rather than any successful run.
    pr_data=json.loads(gh('pr','view',str(pr),'-R',repo,'--json','headRefName,headRefOid,baseRefName,number'))
    need(pr_data.get('headRefName')==BRANCH and pr_data.get('headRefOid')==sha,'R09A_PR_HEAD_MISMATCH')
    runs=json.loads(gh('api',f'repos/{repo}/actions/runs?event=pull_request&per_page=100')).get('workflow_runs',[])
    matches=[r for r in runs if r.get('head_sha')==sha and r.get('head_branch')==BRANCH and r.get('name')==WORKFLOW]
    need(matches,'R09A_RUN_NOT_DISCOVERED')
    r=max(matches,key=lambda x:x.get('id',0))
    run=r['id'];attempt=r.get('run_attempt')
    need(r.get('status')=='completed' and r.get('conclusion')=='success' and type(attempt) is int and attempt>0,'R09A_RUN_NOT_QUALIFIED')
    jobs=json.loads(gh('api',f'repos/{repo}/actions/runs/{run}/jobs?per_page=100')).get('jobs',[])
    selected=[j for j in jobs if j.get('name')=='go-contract']
    need(len(selected)==1 and selected[0].get('conclusion')=='success','R09A_GO_JOB_NOT_PASSED')
    stages=[s for s in selected[0].get('steps',[]) if s.get('name')==STEP]
    need(len(stages)==1 and stages[0].get('conclusion')=='success','R09A_STEP_NOT_PASSED')
    log=gh('run','view',str(run),'--job',str(selected[0]['id']),'--log','-R',repo)
    passes=re.findall(r'XBS_R09A_ACTUAL_BACKEND_PASS cases=(\d+) repeated=(\d+) fuzz_targets=(\d+) fuzz_execs=(\d+) coverage_pct=([\d.]+)',log)
    need(len(passes)==1,'R09A_LOG_RECEIPT_MISSING')
    artifacts=json.loads(gh('api',f'repos/{repo}/actions/runs/{run}/artifacts?per_page=100')).get('artifacts',[])
    name=f'xbs-android-module-{run}-{attempt}'
    selected=[a for a in artifacts if a.get('name')==name and not a.get('expired') and a.get('size_in_bytes',0)>0]
    need(len(selected)==1,'R09A_SOURCE_ARTIFACT_MISSING')
    with tempfile.TemporaryDirectory(prefix='xbs-r09a-gh-') as temp:
        path=Path(temp)
        gh('run','download',str(run),'--name',name,'--dir',str(path),'-R',repo,timeout=210)
        receipts=list(path.rglob('r09a-backend-qualification.json'))
        profiles=list(path.rglob('r09a-backend.coverprofile'))
        need(len(receipts)==len(profiles)==1,'R09A_RECEIPT_OR_COVERAGE_MISSING')
        need(not receipts[0].is_symlink() and not profiles[0].is_symlink(),'R09A_ARTIFACT_SYMLINK_REFUSED')
        values=check_receipt(json.loads(receipts[0].read_text()),sha,run,attempt,profiles[0].read_bytes())
    ln=passes[0]
    need((str(values['tests']),str(values['repeated_cases']),'5',str(values['fuzz_executions']),str(values['coverage_pct']))==ln,'R09A_HOSTED_LOG_ARTIFACT_MISMATCH')
    print('XBS_R09A_VERIFIED '+json.dumps({'sha':sha,'run':run,'attempt':attempt,**values},sort_keys=True),flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--sha',required=True);p.add_argument('--pr',type=int,required=True);p.add_argument('--repo',default=REPO)
    a=p.parse_args()
    try:verify(a.repo,a.sha,a.pr)
    except (Refused,ValueError,KeyError,json.JSONDecodeError) as e:
        print('XBS_R09A_REFUSED '+str(e),file=sys.stderr)
        raise SystemExit(2)
