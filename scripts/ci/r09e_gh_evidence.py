#!/usr/bin/env python3
"""Independent, read-only R09E GitHub source/test/coverage/artifact auditor."""
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
BRANCH='feature/xbs-r09e-seven-package-boundaries-20261114'
BASE='feature/xbs-r09d-risk-coverage-20261114'
WORKFLOW='Devtool XBS Go and Android candidates'
STEP='R09E seven-package boundaries and measured coverage non-regression'
P=Path(__file__).with_name('r09e_boundary_coverage.py')
spec=importlib.util.spec_from_file_location('r09e_policy',P)
assert spec and spec.loader
M=importlib.util.module_from_spec(spec);spec.loader.exec_module(M)
class Refused(RuntimeError):pass
def need(ok,msg):
 if not ok:raise Refused(msg)
def gh(*args,timeout=180):
 try:p=subprocess.run(['gh',*args],capture_output=True,text=True,errors='replace',timeout=timeout)
 except (OSError,subprocess.TimeoutExpired) as e:raise Refused('GH_MISSING_OR_TIMEOUT '+repr(args[:4])) from e
 if p.returncode:raise Refused('GH_COMMAND_FAILED '+repr(args[:4])+' rc='+str(p.returncode)+' '+p.stderr[-600:])
 return p.stdout

def check_run(run,sha):
 need(run.get('head_sha')==sha and run.get('head_branch')==BRANCH,'RUN_WRONG_SOURCE')
 need(run.get('event')=='pull_request' and run.get('name')==WORKFLOW,'RUN_WRONG_EVENT')
 need(run.get('status')=='completed' and run.get('conclusion')=='success','RUN_NOT_GREEN')
 need(type(run.get('id')) is int and type(run.get('run_attempt')) is int and run['run_attempt']>0,'RUN_NO_ID_OR_ATTEMPT')
 return run['id'],run['run_attempt']

def check_jobs(jobs):
 found={}
 for name in ('go-contract','android-companion-jvm','android-companion-api36'):
  hits=[x for x in jobs if x.get('name')==name]
  need(len(hits)==1 and hits[0].get('status')=='completed' and hits[0].get('conclusion')=='success','REQUIRED_JOB_NOT_GREEN '+name)
  found[name]=hits[0]
 for step in (STEP,'R09D crash recovery and complete risk coverage inventory'):
  hits=[x for x in found['go-contract'].get('steps',[]) if x.get('name')==step]
  need(len(hits)==1 and hits[0].get('conclusion')=='success','REQUIRED_STEP_NOT_GREEN '+step)
 return found

def check_log(raw,receipt):
 hits=re.findall(r'XBS_R09E_HOSTED_BOUNDARY_PASS tests=(\d+) repeat=(\d+) race=(\d+) coverage=([\d.]+) baseline=([\d.]+) delta_pp=(-?[\d.]+)',raw)
 need(len(hits)==1,'R09E_LOG_MARKER_DUPLICATE_OR_MISSING')
 tests,repeat,race,coverage,baseline,delta=hits[0]
 need((int(tests),int(repeat),int(race),float(coverage),float(baseline),float(delta))==(
  receipt['root_tests'],receipt['repeat_pass_events'],receipt['race_pass_events'],
  receipt['coverage']['current_full_pct'],receipt['coverage']['baseline_full_pct'],receipt['coverage']['measured_change_pp']),
  'LOG_RECEIPT_COVERAGE_DRIFT')

def audit(repo,sha,pr):
 need(re.fullmatch('[a-f0-9]{40}',sha) is not None,'INVALID_SOURCE_SHA')
 p=json.loads(gh('pr','view',str(pr),'-R',repo,'--json','number,headRefName,headRefOid,baseRefName'))
 need(p.get('headRefName')==BRANCH and p.get('headRefOid')==sha and p.get('baseRefName')==BASE,'PR_WRONG_STACK_OR_SOURCE')
 runs=json.loads(gh('api',f'repos/{repo}/actions/runs?event=pull_request&per_page=100')).get('workflow_runs',[])
 hits=[x for x in runs if x.get('head_sha')==sha and x.get('head_branch')==BRANCH and x.get('name')==WORKFLOW]
 need(bool(hits),'EXACT_SHA_RUN_NOT_VISIBLE')
 latest=max(hits,key=lambda x:x.get('id',0));rid,attempt=check_run(latest,sha)
 jobs=json.loads(gh('api',f'repos/{repo}/actions/runs/{rid}/jobs?per_page=100')).get('jobs',[])
 go=check_jobs(jobs)['go-contract']
 log=gh('run','view',str(rid),'--job',str(go['id']),'--log','-R',repo,timeout=240)
 artifacts=json.loads(gh('api',f'repos/{repo}/actions/runs/{rid}/artifacts?per_page=100')).get('artifacts',[])
 name=f'xbs-android-module-{rid}-{attempt}'
 hits=[x for x in artifacts if x.get('name')==name]
 need(len(hits)==1 and hits[0].get('expired') is False and type(hits[0].get('size_in_bytes')) is int and hits[0]['size_in_bytes']>0,'ARTIFACT_MISSING_EXPIRED_OR_DUPLICATE')
 with tempfile.TemporaryDirectory(prefix='xbs-r09e-gh-') as temp:
  root=Path(temp)
  gh('run','download',str(rid),'--name',name,'--dir',str(root),'-R',repo,timeout=300)
  names=('r09e-boundary-coverage.json','r09d-risk-coverage.json','r09d-risk.coverprofile')
  found=[]
  for n in names:
   h=list(root.rglob(n));need(len(h)==1 and h[0].is_file() and not h[0].is_symlink(),'RECEIPT_PROFILE_INVENTORY_WRONG '+n)
   found.append(h[0])
  r09e=json.loads(found[0].read_text());r09d=json.loads(found[1].read_text());profile=found[2].read_bytes()
  summary=M.validate_receipt(r09e,sha,rid,attempt,r09d,profile)
  check_log(log,r09e)
 print('XBS_R09E_ACTUAL_HOSTED_QUALIFIED '+json.dumps({'run_id':rid,'attempt':attempt,'sha':sha,
  **summary,'scope':'hosted_go_jvm_api36_no_root_or_release'},sort_keys=True),flush=True)

def main():
 p=argparse.ArgumentParser();p.add_argument('--repo',default=REPO);p.add_argument('--sha',required=True);p.add_argument('--pr',type=int,required=True)
 a=p.parse_args()
 try:audit(a.repo,a.sha,a.pr)
 except (Refused,M.Refused,ValueError,OSError) as e:
  print('XBS_R09E_REFUSED '+str(e),file=sys.stderr);raise SystemExit(2)
if __name__=='__main__':main()
