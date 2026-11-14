#!/usr/bin/env python3
"""Independent, read-only GitHub R09F final hosted-run ledger auditor."""
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
BRANCH='feature/xbs-r09f-adversarial-final-ledger-20261114'
BASE='feature/xbs-r09e-seven-package-boundaries-20261114'
WORKFLOW='Devtool XBS Go and Android candidates'
STEP='R09F adversarial recovery and final hosted evidence ledger'
P=Path(__file__).with_name('r09f_final_ledger.py')
spec=importlib.util.spec_from_file_location('r09f_policy',P)
assert spec and spec.loader
M=importlib.util.module_from_spec(spec);spec.loader.exec_module(M)
class Refused(RuntimeError):pass
def need(ok,reason):
 if not ok:raise Refused(reason)
def gh(*args,timeout=200):
 try:p=subprocess.run(['gh',*args],text=True,capture_output=True,errors='replace',timeout=timeout)
 except (OSError,subprocess.TimeoutExpired) as e:raise Refused('GH_MISSING_OR_TIMEOUT '+repr(args[:4])) from e
 if p.returncode:raise Refused('GH_FAILED rc='+str(p.returncode)+' '+repr(args[:4])+' '+p.stderr[-600:])
 return p.stdout

def check_run(run,sha):
 need(run.get('head_sha')==sha and run.get('head_branch')==BRANCH,'RUN_WRONG_SHA_OR_BRANCH')
 need(run.get('event')=='pull_request' and run.get('name')==WORKFLOW,'RUN_WRONG_EVENT_OR_WORKFLOW')
 need(run.get('status')=='completed' and run.get('conclusion')=='success','RUN_NOT_GREEN')
 need(type(run.get('id')) is int and type(run.get('run_attempt')) is int and run['id']>0 and run['run_attempt']>0,'RUN_IDENTITY_MISSING')
 return run['id'],run['run_attempt']

def check_jobs(jobs):
 out={}
 for name in ('go-contract','android-companion-jvm','android-companion-api36'):
  hits=[j for j in jobs if j.get('name')==name]
  need(len(hits)==1 and hits[0].get('status')=='completed' and hits[0].get('conclusion')=='success','MANDATORY_JOB_NOT_GREEN '+name)
  out[name]=hits[0]
 names=('R09A bounded backend fuzz, repeat and coverage',
        'R09B deterministic fault injection and zip negatives',
        'R09C fake Android backend and CLI golden contracts',
        'R09D crash recovery and complete risk coverage inventory',
        'R09E seven-package boundaries and measured coverage non-regression',STEP)
 for name in names:
  match=[s for s in out['go-contract'].get('steps',[]) if s.get('name')==name]
  need(len(match)==1 and match[0].get('conclusion')=='success','REQUIRED_STAGE_NOT_GREEN '+name)
 return out

def check_log(raw,data):
 hits=re.findall(r'XBS_R09F_ADVERSARIAL_PASS tests=(\d+) subtests=(\d+) repeat=(\d+) repeat_subtests=(\d+) race=(\d+) race_subtests=(\d+) coverage=([\d.]+) stages=(\d+)',raw)
 need(len(hits)==1,'FINAL_LOG_MARKER_MISSING_OR_DUPLICATE')
 a,b,c,d,e,f,pct,stages=hits[0]
 need(tuple(map(int,(a,b,c,d,e,f,stages)))==(6,88,18,264,6,88,5),'FINAL_LOG_TEST_OR_STAGE_DRIFT')
 need(float(pct)==data['coverage']['full_internal_pct'],'FINAL_LOG_COVERAGE_DRIFT')

def audit(repo,sha,pr):
 need(re.fullmatch('[a-f0-9]{40}',sha) is not None,'SOURCE_SHA_INVALID')
 p=json.loads(gh('pr','view',str(pr),'-R',repo,'--json','number,headRefName,headRefOid,baseRefName'))
 need(p.get('headRefName')==BRANCH and p.get('headRefOid')==sha and p.get('baseRefName')==BASE,'PR_STACK_OR_SOURCE_DRIFT')
 runs=json.loads(gh('api',f'repos/{repo}/actions/runs?event=pull_request&per_page=100')).get('workflow_runs',[])
 hits=[x for x in runs if x.get('head_sha')==sha and x.get('head_branch')==BRANCH and x.get('name')==WORKFLOW]
 need(bool(hits),'EXACT_RUN_NOT_DISCOVERED')
 run=max(hits,key=lambda x:x.get('id',0));rid,attempt=check_run(run,sha)
 jobs=json.loads(gh('api',f'repos/{repo}/actions/runs/{rid}/jobs?per_page=100')).get('jobs',[])
 go=check_jobs(jobs)['go-contract']
 log=gh('run','view',str(rid),'--job',str(go['id']),'--log','-R',repo,timeout=300)
 arts=json.loads(gh('api',f'repos/{repo}/actions/runs/{rid}/artifacts?per_page=100')).get('artifacts',[])
 name=f'xbs-android-module-{rid}-{attempt}'
 matches=[a for a in arts if a.get('name')==name]
 need(len(matches)==1 and matches[0].get('expired') is False and type(matches[0].get('size_in_bytes')) is int and matches[0]['size_in_bytes']>0,
      'MODULE_ARTIFACT_ABSENT_EXPIRED_OR_DUPLICATED')
 with tempfile.TemporaryDirectory(prefix='xbs-r09f-audit-') as tmp:
  root=Path(tmp)
  gh('run','download',str(rid),'--name',name,'--dir',str(root),'-R',repo,timeout=320)
  expected=(*M.STAGES.values(),'r09d-risk.coverprofile','r09f-final-ledger.json')
  evidence={}
  for filename in expected:
   paths=list(root.rglob(filename))
   need(len(paths)==1 and paths[0].is_file() and not paths[0].is_symlink(),'MISSING_DUPLICATE_OR_UNSAFE_MEMBER '+filename)
   evidence[filename]=paths[0].read_bytes()
  receipt=json.loads(evidence.pop('r09f-final-ledger.json'))
  summary=M.validate_receipt(receipt,sha,rid,attempt,evidence)
  check_log(log,receipt)
 print('XBS_R09F_ACTUAL_HOSTED_QUALIFIED '+json.dumps({'run_id':rid,'attempt':attempt,'sha':sha,
  **summary,'scope':'hosted_go_jvm_api36_only_no_physical_root_no_release'},sort_keys=True),flush=True)

def main():
 p=argparse.ArgumentParser();p.add_argument('--repo',default=REPO);p.add_argument('--sha',required=True);p.add_argument('--pr',type=int,required=True);a=p.parse_args()
 try:audit(a.repo,a.sha,a.pr)
 except (Refused,M.Refused,ValueError,OSError,TypeError) as e:
  print('XBS_R09F_REFUSED '+str(e),file=sys.stderr);raise SystemExit(2)
if __name__=='__main__':main()
