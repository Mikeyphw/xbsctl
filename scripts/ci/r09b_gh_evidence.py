#!/usr/bin/env python3
"""R09B independent observed GitHub fault-matrix receipt audit, strictly read-only."""
from __future__ import annotations
import argparse
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile

REPO='Mikeyphw/xbsctl'
BRANCH='feature/xbs-r09b-backend-fault-matrix-20261114'
WORKFLOW='Devtool XBS Go and Android candidates'
STEP='R09B deterministic fault injection and zip negatives'
SPEC=importlib.util.spec_from_file_location('r09b_matrix_policy',Path(__file__).with_name('r09b_backend_matrix.py'))
assert SPEC and SPEC.loader
M=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(M)
class Refused(RuntimeError):pass
def need(ok,msg):
    if not ok:raise Refused(msg)
def gh(*args,timeout=150):
    try:v=subprocess.run(['gh',*args],capture_output=True,text=True,errors='replace',timeout=timeout)
    except (OSError,subprocess.TimeoutExpired) as e:raise Refused('GH_FAILED_OR_TIMEOUT '+repr(args[:3])) from e
    if v.returncode:raise Refused('GH_COMMAND_FAILED '+repr(args[:3])+' rc='+str(v.returncode)+' '+v.stderr[-550:])
    return v.stdout
def audit_run(run,sha):
    need(run.get('head_sha')==sha and run.get('head_branch')==BRANCH,'RUN_WRONG_HEAD')
    need(run.get('event')=='pull_request' and run.get('name')==WORKFLOW,'RUN_WRONG_EVENT_OR_WORKFLOW')
    need(run.get('status')=='completed' and run.get('conclusion')=='success','RUN_NOT_GREEN')
    need(type(run.get('id')) is int and type(run.get('run_attempt')) is int and run['run_attempt']>0,'RUN_IDENTITY_MISSING')
    return run['id'],run['run_attempt']
def audit_jobs(jobs):
    required=['go-contract','android-companion-jvm','android-companion-api36']
    out={}
    for name in required:
        matches=[x for x in jobs if x.get('name')==name]
        need(len(matches)==1 and matches[0].get('status')=='completed' and matches[0].get('conclusion')=='success','REQUIRED_JOB_NOT_GREEN '+name)
        out[name]=matches[0]
    steps=[s for s in out['go-contract'].get('steps',[]) if s.get('name')==STEP]
    need(len(steps)==1 and steps[0].get('conclusion')=='success','R09B_REQUIRED_STEP_MISSING')
    return out
def validate_log(log,data):
    matches=re.findall(r'XBS_R09B_BACKEND_MATRIX_PASS tests=(\d+) repeat=(\d+) race=(\d+) domains=(\d+)',log)
    need(len(matches)==1,'R09B_LOG_MISSING_OR_DUPLICATE')
    tests,repeat,race,domains=map(int,matches[0])
    need((tests,repeat,race,domains)==(data['required_root_tests'],data['repeat_passed_root_events'],data['race_passed_root_events'],4),'R09B_LOG_RECEIPT_MISMATCH')
def verify(repo,sha,pr):
    need(re.fullmatch('[a-f0-9]{40}',sha) is not None,'SOURCE_SHA_INVALID')
    pr_data=json.loads(gh('pr','view',str(pr),'-R',repo,'--json','headRefName,headRefOid,baseRefName,number'))
    need(pr_data.get('headRefName')==BRANCH and pr_data.get('headRefOid')==sha,'R09B_PR_SOURCE_DRIFT')
    runs=json.loads(gh('api',f'repos/{repo}/actions/runs?event=pull_request&per_page=100')).get('workflow_runs',[])
    matches=[r for r in runs if r.get('head_sha')==sha and r.get('head_branch')==BRANCH and r.get('name')==WORKFLOW]
    need(bool(matches),'R09B_EXACT_PR_RUN_NOT_DISCOVERED')
    newest=max(matches,key=lambda r:r.get('id',0));run_id,attempt=audit_run(newest,sha)
    jobs=json.loads(gh('api',f'repos/{repo}/actions/runs/{run_id}/jobs?per_page=100')).get('jobs',[])
    selected=audit_jobs(jobs)
    log=gh('run','view',str(run_id),'--job',str(selected['go-contract']['id']),'--log','-R',repo,timeout=200)
    artifacts=json.loads(gh('api',f'repos/{repo}/actions/runs/{run_id}/artifacts?per_page=100')).get('artifacts',[])
    name=f'xbs-android-module-{run_id}-{attempt}'
    hits=[a for a in artifacts if a.get('name')==name]
    need(len(hits)==1 and hits[0].get('expired') is False and type(hits[0].get('size_in_bytes')) is int and hits[0]['size_in_bytes']>0,'R09B_REQUIRED_ARTIFACT_ABSENT')
    with tempfile.TemporaryDirectory(prefix='xbs-r09b-audit-') as td:
        p=Path(td)
        gh('run','download',str(run_id),'--name',name,'--dir',str(p),'-R',repo,timeout=240)
        receipts=list(p.rglob('r09b-backend-matrix.json'))
        need(len(receipts)==1 and receipts[0].is_file() and not receipts[0].is_symlink(),'R09B_SOURCE_RECEIPT_MISSING')
        try:data=json.loads(receipts[0].read_text())
        except (OSError,ValueError) as e:raise Refused('R09B_RECEIPT_JSON_INVALID') from e
        summary=M.validate_receipt(data,sha,run_id,attempt)
        validate_log(log,data)
    print('XBS_R09B_ACTUAL_HOSTED_QUALIFIED '+json.dumps({'sha':sha,'run_id':run_id,'attempt':attempt,
        **summary,'scope':'hosted_go_and_api36_no_physical_root_no_release'},sort_keys=True),flush=True)

def main():
    a=argparse.ArgumentParser();a.add_argument('--repo',default=REPO);a.add_argument('--sha',required=True);a.add_argument('--pr',required=True,type=int)
    v=a.parse_args()
    try:verify(v.repo,v.sha,v.pr)
    except (Refused,M.Refused) as e:
        print('XBS_R09B_REFUSED '+str(e),file=sys.stderr)
        raise SystemExit(2)
if __name__=='__main__':main()
