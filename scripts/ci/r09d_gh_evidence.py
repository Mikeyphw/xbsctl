#!/usr/bin/env python3
"""R09D read-only GitHub job/log/artifact receipt verifier.

No dispatch, repo mutation, device operation, release signing or root access.
"""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile

REPO='Mikeyphw/xbsctl'
BRANCH='feature/xbs-r09d-risk-coverage-20261114'
BASE='feature/xbs-r09c-fake-android-cli-20261114'
WORKFLOW='Devtool XBS Go and Android candidates'
STEP='R09D crash recovery and complete risk coverage inventory'
P=Path(__file__).with_name('r09d_risk_coverage.py')
spec=importlib.util.spec_from_file_location('r09d_risk_policy',P)
assert spec and spec.loader
M=importlib.util.module_from_spec(spec);spec.loader.exec_module(M)
class Refused(RuntimeError):pass
def need(ok,reason):
 if not ok:raise Refused(reason)
def gh(*args,timeout=150):
 try:p=subprocess.run(['gh',*args],capture_output=True,text=True,errors='replace',timeout=timeout)
 except (OSError,subprocess.TimeoutExpired) as e:raise Refused('GH_INVOCATION_TIMEOUT '+repr(args[:3])) from e
 if p.returncode:raise Refused('GH_FAILED '+repr(args[:4])+' rc='+str(p.returncode)+' '+p.stderr[-700:])
 return p.stdout

def check_run(run,sha):
 need(run.get('head_sha')==sha and run.get('head_branch')==BRANCH,'RUN_SHA_OR_BRANCH_DRIFT')
 need(run.get('event')=='pull_request' and run.get('name')==WORKFLOW,'RUN_WORKFLOW_EVENT_WRONG')
 need(run.get('status')=='completed' and run.get('conclusion')=='success','RUN_NOT_GREEN')
 need(type(run.get('id')) is int and type(run.get('run_attempt')) is int and run['run_attempt']>0,'RUN_ID_ATTEMPT_MISSING')
 return run['id'],run['run_attempt']

def check_jobs(jobs):
 required={'go-contract','android-companion-jvm','android-companion-api36'}
 found={}
 for name in required:
  matches=[x for x in jobs if x.get('name')==name]
  need(len(matches)==1 and matches[0].get('status')=='completed' and matches[0].get('conclusion')=='success','REQUIRED_JOB_FAILED '+name)
  found[name]=matches[0]
 stages=[x for x in found['go-contract'].get('steps',[]) if x.get('name')==STEP]
 need(len(stages)==1 and stages[0].get('conclusion')=='success','R09D_GO_STEP_NOT_GREEN')
 return found

def check_log(text,receipt):
 matches=re.findall(r'XBS_R09D_REAL_BACKEND_PASS tests=(\d+) repeat=(\d+) race=(\d+) risk_packages=(\d+) total_coverage_pct=([\d.]+) below_target=(\d+) hosted_skips=(\d+)',text)
 need(len(matches)==1,'R09D_LOG_MARKER_NOT_UNIQUE')
 tests,repeat,race,pkgs,pct,below,skips=matches[0]
 need((int(tests),int(repeat),int(race),int(pkgs),float(pct),int(below),int(skips))==(
   receipt['required_root_tests'],receipt['repeat_root_events'],receipt['race_root_events'],len(M.RISK),
   receipt['all_internal_coverage_pct'],len(receipt['unqualified_risk_targets']),
   len(M.EXPECTED_HOSTED_SKIPS)),
   'R09D_LOG_AND_RECEIPT_DISAGREE')

def audit(repo,sha,pr):
 need(re.fullmatch(r'[a-f0-9]{40}',sha) is not None,'SOURCE_SHA_INVALID')
 p=json.loads(gh('pr','view',str(pr),'-R',repo,'--json','number,headRefName,headRefOid,baseRefName'))
 need(p.get('headRefName')==BRANCH and p.get('baseRefName')==BASE and p.get('headRefOid')==sha,'PR_SOURCE_OR_STACK_DRIFT')
 runs=json.loads(gh('api',f'repos/{repo}/actions/runs?event=pull_request&per_page=100')).get('workflow_runs',[])
 hits=[x for x in runs if x.get('head_sha')==sha and x.get('head_branch')==BRANCH and x.get('name')==WORKFLOW]
 need(hits,'EXACT_PR_RUN_NOT_FOUND')
 selected=max(hits,key=lambda x:x.get('id',0));rid,attempt=check_run(selected,sha)
 jobs=json.loads(gh('api',f'repos/{repo}/actions/runs/{rid}/jobs?per_page=100')).get('jobs',[])
 selected_job=check_jobs(jobs)['go-contract']
 log=gh('run','view',str(rid),'--job',str(selected_job['id']),'--log','-R',repo,timeout=220)
 artifacts=json.loads(gh('api',f'repos/{repo}/actions/runs/{rid}/artifacts?per_page=100')).get('artifacts',[])
 wanted=f'xbs-android-module-{rid}-{attempt}'
 matches=[x for x in artifacts if x.get('name')==wanted]
 need(len(matches)==1 and matches[0].get('expired') is False and type(matches[0].get('size_in_bytes')) is int and matches[0]['size_in_bytes']>0,'R09D_ARTIFACT_MISSING_EXPIRED_OR_DUPLICATED')
 with tempfile.TemporaryDirectory(prefix='xbs-r09d-github-') as tmp:
  root=Path(tmp)
  gh('run','download',str(rid),'--name',wanted,'--dir',str(root),'-R',repo,timeout=240)
  receipts=list(root.rglob('r09d-risk-coverage.json'))
  profiles=list(root.rglob('r09d-risk.coverprofile'))
  need(len(receipts)==len(profiles)==1,'R09D_RECEIPT_OR_PROFILE_INVENTORY')
  need(all(x.is_file() and not x.is_symlink() for x in receipts+profiles),'R09D_ARTIFACT_MEMBER_NOT_REGULAR')
  data=json.loads(receipts[0].read_text())
  verified=M.validate_receipt(data,sha,rid,attempt,profiles[0].read_bytes())
  check_log(log,data)
 print('XBS_R09D_ACTUAL_HOSTED_QUALIFIED '+json.dumps({'source_sha':sha,'run_id':rid,'attempt':attempt,
  **verified,'scope':'hosted_go_jvm_api36_only_no_root_device_or_release'},sort_keys=True),flush=True)

def main():
 p=argparse.ArgumentParser();p.add_argument('--repo',default=REPO);p.add_argument('--sha',required=True);p.add_argument('--pr',type=int,required=True);a=p.parse_args()
 try:audit(a.repo,a.sha,a.pr)
 except (Refused,M.Refused,ValueError) as e:
  print('XBS_R09D_REFUSED '+str(e),file=sys.stderr);raise SystemExit(2)
if __name__=='__main__':main()
