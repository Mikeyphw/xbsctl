#!/usr/bin/env python3
"""R09E hosted-only seven-package boundary matrix and non-regressing full coverage.

R09D is the full-suite source of truth. R09E never substitutes selected-test
coverage for that authoritative measurement, nor claims device/root safety.
"""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys

SCHEMA='xbs-r09e-seven-package-boundaries/v1'
REPO='Mikeyphw/xbsctl'
PKG_PREFIX='github.com/Mikeyphw/xbsctl/internal/'
RISK=('cli','config','control','daemon','eventbridge','module','tuning')
ROOTS={k:'TestR09E'+v for k,v in {
 'cli':'CLIArgumentAndJSONRefusals', 'config':'ConfigBoundaryGuards',
 'control':'ControlAckIdentifierBoundary', 'daemon':'DaemonArgumentAndEventBoundaries',
 'eventbridge':'BridgeNumericAndDigestBoundaries',
 'module':'ModuleCandidateEvidenceRefusals',
 'tuning':'TuningAndroidProbeOutputRefusals'}.items()}
PACKAGES=['./internal/'+p for p in RISK]
BASELINE_SHA='f8218222993940688df715d0ee32b353ff8092b5'
BASELINE_FULL_PCT=71.29
TARGET_PER_PACKAGE=50
class Refused(RuntimeError):pass

def need(ok,msg):
 if not ok:raise Refused(msg)

def companion():
 path=Path(__file__).with_name('r09d_risk_coverage.py')
 need(path.is_file() and not path.is_symlink(),'MISSING_SOURCE_BOUND_R09D_COMPANION')
 spec=importlib.util.spec_from_file_location('r09e_r09d',path)
 need(spec is not None and spec.loader is not None,'R09D_COMPANION_IMPORT_INVALID')
 mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
 return mod

def run(cmd,repo,timeout=400):
 try:p=subprocess.run(cmd,cwd=repo,text=True,capture_output=True,errors='replace',timeout=timeout)
 except (OSError,subprocess.TimeoutExpired) as e:raise Refused('COMMAND_MISSING_OR_TIMEOUT '+repr(cmd[:5])) from e
 if p.returncode:
  raise Refused('COMMAND_FAILED rc='+str(p.returncode)+' '+repr(cmd[:5])+'\n'+(p.stdout+'\n'+p.stderr)[-6000:])
 return p.stdout

def parse_tests(raw,repeat=1):
 require={(PKG_PREFIX+k,v) for k,v in ROOTS.items()}
 root_pass={};package_pass={};failed=[];seen_run={}
 for ln,line in enumerate(raw.splitlines(),1):
  if not line.strip():continue
  try:row=json.loads(line)
  except ValueError as e:raise Refused('INVALID_GO_JSON '+str(ln)) from e
  need(isinstance(row,dict),'NONOBJECT_GO_EVENT '+str(ln))
  event=row.get('Action');pkg=row.get('Package');test=row.get('Test')
  if event in ('fail','skip'):failed.append((pkg,test,event))
  if event=='run' and isinstance(test,str) and '/' not in test:
   key=(pkg,test);seen_run[key]=seen_run.get(key,0)+1
  if event=='pass':
   if isinstance(test,str) and test and '/' not in test:
    key=(pkg,test);root_pass[key]=root_pass.get(key,0)+1
   elif not test and isinstance(pkg,str):
    package_pass[pkg]=package_pass.get(pkg,0)+1
 need(not failed,'FAILED_OR_SKIPPED_BOUNDARY_TEST '+repr(failed))
 need(set(root_pass)==require,'BOUNDARY_TEST_INVENTORY_DRIFT '+repr(sorted(set(root_pass)^require)))
 need(all(root_pass[k]==repeat and seen_run.get(k)==repeat for k in require),'MISSING_REPETITIONS_OR_GO_RUN_EVENTS')
 need(package_pass=={PKG_PREFIX+k:1 for k in RISK},'MISSING_OR_DUPLICATE_GO_PACKAGE_PASS '+repr(package_pass))
 return {'root_tests':len(require),'root_pass_events':sum(root_pass.values()),
         'required_test_ids':sorted(p+'::'+t for p,t in require),'packages_passed':len(package_pass),
         'failed_tests':0,'skipped_tests':0}

def coverage_binding(r09d,profile,sha,runid,attempt):
 D=companion()
 need(r09d.get('xbs_source_sha')==sha,'R09D_COVERAGE_SOURCE_DRIFT')
 D.validate_receipt(r09d,sha,runid,attempt,profile)
 pct=r09d.get('all_internal_coverage_pct')
 need(type(pct) in (int,float) and pct>=BASELINE_FULL_PCT,'R09E_COVERAGE_REGRESSED_FROM_R09D_BASELINE')
 risk=r09d.get('risk_packages')
 need(isinstance(risk,dict) and set(risk)==set(RISK),'R09E_RISK_PACKAGE_INVENTORY')
 need(all(type(risk[k].get('coverage_pct')) in (int,float) and risk[k]['coverage_pct']>=TARGET_PER_PACKAGE for k in RISK),'R09E_RISK_PACKAGE_BELOW_50_PCT')
 need(r09d.get('r09_full_coverage_gate_qualified') is True and r09d.get('unqualified_risk_targets')==[],'R09E_R09D_COVERAGE_UNQUALIFIED')
 return {'baseline_sha':BASELINE_SHA,'baseline_full_pct':BASELINE_FULL_PCT,
         'current_full_pct':pct,'measured_change_pp':round(pct-BASELINE_FULL_PCT,2),
         'risk_package_percentages':{k:risk[k]['coverage_pct'] for k in RISK},
         'coverage_profile_sha256':hashlib.sha256(profile).hexdigest()}

def validate_receipt(data,sha,runid,attempt,r09d,profile):
 need(isinstance(data,dict) and data.get('schema')==SCHEMA,'R09E_SCHEMA_DRIFT')
 need(data.get('xbs_source_sha')==sha and data.get('github_run_id')==str(runid) and
      data.get('github_run_attempt')==str(attempt),'R09E_RUN_OR_SOURCE_DRIFT')
 expected=sorted(PKG_PREFIX+k+'::'+v for k,v in ROOTS.items())
 need(data.get('required_test_ids')==expected and data.get('root_tests')==7 and
      data.get('root_pass_events')==7 and data.get('repeat_pass_events')==21 and
      data.get('race_pass_events')==7 and data.get('packages_passed')==7 and
      data.get('race_packages')==7,'R09E_REAL_TEST_EVIDENCE_MISSING')
 need(data.get('failed_tests')==data.get('skipped_tests')==0,'R09E_FAILED_OR_SKIPPED')
 need(all(data.get(k) is False for k in ('root_executed','device_mutated','physical_root_qualified','release_qualified')),'R09E_FALSE_DEVICE_OR_RELEASE_CLAIM')
 expected_coverage=coverage_binding(r09d,profile,sha,runid,attempt)
 need(data.get('coverage')==expected_coverage,'R09E_COVERAGE_BINDING_OR_HASH_DRIFT')
 return {'tests':7,'repeated':21,'raced':7,**expected_coverage}

def hosted(repo):
 need(os.environ.get('GITHUB_ACTIONS')=='true' and os.environ.get('GITHUB_EVENT_NAME') in ('pull_request','push','workflow_dispatch'),'NOT_HOSTED_ACTIONS')
 need(os.environ.get('XBS_ROOT_IT_ACK') is None,'ROOT_ACK_FORBIDDEN')
 if hasattr(os,'geteuid'):need(os.geteuid()!=0,'HOSTED_ROOT_FORBIDDEN')
 sha=run(['git','rev-parse','HEAD'],repo).strip()
 need(re.fullmatch('[0-9a-f]{40}',sha) is not None and sha==os.environ.get('XBS_EXPECTED_HEAD_SHA'),'CHECKOUT_SHA_DRIFT')
 runid=os.environ.get('GITHUB_RUN_ID','');attempt=os.environ.get('GITHUB_RUN_ATTEMPT','')
 need(runid.isdecimal() and attempt.isdecimal() and int(runid)>0 and int(attempt)>0,'RUN_ID_OR_ATTEMPT_MISSING')
 folder=repo/'dist';need(folder.is_dir() and not folder.is_symlink(),'UNSAFE_R09D_COVERAGE_DIRECTORY')
 receipt=folder/'r09e-boundary-coverage.json';need(not receipt.exists() and not receipt.is_symlink(),'STALE_R09E_RECEIPT')
 r09d=json.loads((folder/'r09d-risk-coverage.json').read_text())
 profile=(folder/'r09d-risk.coverprofile').read_bytes()
 baseline=parse_tests(run(['go','test','-json','-count=1','-run','^TestR09E',*PACKAGES],repo,420))
 repeated=parse_tests(run(['go','test','-json','-count=3','-run','^TestR09E',*PACKAGES],repo,480),3)
 raced=parse_tests(run(['go','test','-race','-json','-count=1','-run','^TestR09E',*PACKAGES],repo,560))
 data={**baseline,'schema':SCHEMA,'xbs_source_sha':sha,'github_run_id':runid,
       'github_run_attempt':attempt,'repeat_pass_events':repeated['root_pass_events'],
       'race_pass_events':raced['root_pass_events'],'race_packages':raced['packages_passed'],
       'coverage':coverage_binding(r09d,profile,sha,int(runid),int(attempt)),
       'root_executed':False,'device_mutated':False,'physical_root_qualified':False,'release_qualified':False}
 validate_receipt(data,sha,int(runid),int(attempt),r09d,profile)
 receipt.write_text(json.dumps(data,sort_keys=True,indent=2)+'\n')
 print('XBS_R09E_HOSTED_BOUNDARY_PASS tests=7 repeat=21 race=7 coverage='+str(data['coverage']['current_full_pct'])+
       ' baseline='+str(BASELINE_FULL_PCT)+' delta_pp='+str(data['coverage']['measured_change_pp']),flush=True)
 print('XBS_R09E_BOUND_RECEIPT sha256='+hashlib.sha256(receipt.read_bytes()).hexdigest(),flush=True)

def main():
 p=argparse.ArgumentParser();p.add_argument('--hosted',action='store_true');p.add_argument('--repo',type=Path,default=Path.cwd());a=p.parse_args()
 try:
  need(a.hosted,'HOSTED_ONLY');hosted(a.repo.resolve())
 except (Refused, OSError, ValueError) as e:
  print('XBS_R09E_REFUSED '+str(e),file=sys.stderr);raise SystemExit(2)
if __name__=='__main__':main()
