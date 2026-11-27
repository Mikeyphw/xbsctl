#!/usr/bin/env python3
"""R09F deterministic rootless adversarial tests and source/run-bound phase ledger.

This lane never runs on devices, never authorizes installs or release, and
cannot reclassify a pending or failed GitHub run as successful.
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

SCHEMA='xbs-r09f-adversarial-final-ledger/v1'
REPO='Mikeyphw/xbsctl'
PREFIX='github.com/Mikeyphw/xbsctl/internal/'
ROOTS={
 'tuning':(
  'TestR09FAdversarialCrashSchedule',
  'TestR09FJournalReleasePermutation',
  'TestR09FExternalStateOwnership'),
 'control':(
  'TestR09FQueueRestartSchedule',
  'TestR09FAckImmutabilitySchedule',
  'TestR09FSymlinkPathRefusalSchedule'),
}
PACKAGES=('./internal/control','./internal/tuning')
SUBTESTS={
 'TestR09FAdversarialCrashSchedule':tuple('round-%d-%s'%(n,p) for n in range(4) for p in ('governor','online','gms','lowram','doze','wifi')),
 'TestR09FJournalReleasePermutation':tuple('round-%d-%s'%(n,p) for n in range(4) for p in ('wifi','gms','governor')),
 'TestR09FExternalStateOwnership':tuple('round-%d-%s'%(n,p) for n in range(4) for p in ('already-offline-wifi','gms-disabled-externally','preforced-doze')),
 'TestR09FQueueRestartSchedule':tuple('restart-%02d'%i for i in range(16)),
 'TestR09FAckImmutabilitySchedule':tuple('ack-%02d'%i for i in range(16)),
 'TestR09FSymlinkPathRefusalSchedule':tuple('symlink-%02d'%i for i in range(8)),
}
STAGES={
 'r09a':'r09a-backend-qualification.json',
 'r09b':'r09b-backend-matrix.json',
 'r09c':'r09c-backend-model.json',
 'r09d':'r09d-risk-coverage.json',
 'r09e':'r09e-boundary-coverage.json',
}
BASELINE_PCT=72.07
class Refused(RuntimeError):pass
def need(ok,why):
 if not ok:raise Refused(why)
def load_companion(filename):
 path=Path(__file__).with_name(filename)
 need(path.is_file() and not path.is_symlink(),'MISSING_COMPANION '+filename)
 spec=importlib.util.spec_from_file_location('r09f_'+filename.replace('.','_'),path)
 need(spec is not None and spec.loader is not None,'COMPANION_IMPORT_REFUSED')
 mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
 return mod

def invoke(argv,repo,timeout=600):
 try:r=subprocess.run(argv,cwd=repo,capture_output=True,text=True,errors='replace',timeout=timeout)
 except (OSError,subprocess.TimeoutExpired) as e:raise Refused('COMMAND_MISSING_OR_TIMEOUT '+repr(argv[:4])) from e
 if r.returncode:
  raise Refused('COMMAND_FAILED rc='+str(r.returncode)+' '+repr(argv[:5])+'\n'+(r.stdout+'\n'+r.stderr)[-6500:])
 return r.stdout

def required_ids():
 return sorted(PREFIX+p+'::'+name for p,names in ROOTS.items() for name in names)
def subtest_ids():
 return sorted(PREFIX+p+'::'+root+'/'+case for p,names in ROOTS.items() for root in names for case in SUBTESTS[root])

def parse_test_events(raw,repetitions=1):
 expected=set(required_ids());children=set(subtest_ids());runs={};passes={};subruns={};subpasses={};pkgpass={};bad=[]
 for lineno,line in enumerate(raw.splitlines(),1):
  if not line.strip():continue
  try:row=json.loads(line)
  except ValueError as e:raise Refused('INVALID_GO_JSON_LINE '+str(lineno)) from e
  need(isinstance(row,dict),'NON_OBJECT_GO_EVENT')
  action=row.get('Action');pkg=row.get('Package');name=row.get('Test')
  if action in ('fail','skip'):bad.append((pkg,name,action))
  if action not in ('run','pass') or not isinstance(pkg,str):continue
  if not name:
   if action=='pass':pkgpass[pkg]=pkgpass.get(pkg,0)+1
   continue
  need(isinstance(name,str),'MALFORMED_TEST_NAME')
  key=pkg+'::'+name
  if '/' in name:
   dest=subruns if action=='run' else subpasses
  else:dest=runs if action=='run' else passes
  dest[key]=dest.get(key,0)+1
 need(not bad,'R09F_FAILED_OR_SKIPPED '+repr(bad[:12]))
 for label,got,want in [('root-run',runs,expected),('root-pass',passes,expected),
                        ('subtest-run',subruns,children),('subtest-pass',subpasses,children)]:
  need(set(got)==want,'R09F_'+label.upper()+'_INVENTORY_DRIFT '+repr(sorted(set(got)^want)[:8]))
  need(all(count==repetitions for count in got.values()),'R09F_'+label.upper()+'_REPETITION_DRIFT')
 need(pkgpass=={PREFIX+'control':1,PREFIX+'tuning':1},'R09F_PACKAGE_PASS_INVENTORY_DRIFT')
 need(len(expected)==6 and len(children)==88,'R09F_EXPECTED_CASE_INVENTORY_DRIFT')
 return {'required_test_ids':sorted(expected),'required_subtest_ids':sorted(children),
         'root_tests':len(expected),'subtests':len(children),'root_pass_events':sum(passes.values()),
         'subtest_pass_events':sum(subpasses.values()),'passed_packages':2,
         'failed_events':0,'skipped_events':0}

def phase_evidence(data_bytes,sha,run_id,attempt):
 ledger={}
 for stage,filename in STAGES.items():
  need(filename in data_bytes,'MISSING_STAGE_RECEIPT '+stage)
  b=data_bytes[filename]
  need(type(b) is bytes and 100<len(b)<2_000_000,'UNSAFE_STAGE_RECEIPT_BYTES '+stage)
  try:doc=json.loads(b)
  except ValueError as e:raise Refused('STAGE_JSON_INVALID '+stage) from e
  need(isinstance(doc,dict) and doc.get('xbs_source_sha')==sha and
       doc.get('github_run_id')==str(run_id) and doc.get('github_run_attempt')==str(attempt),
       'STAGE_SOURCE_RUN_ATTEMPT_DRIFT '+stage)
  flags=('device_mutated','physical_root_qualified','release_qualified')
  # R09A's historical schema predates the explicit root_executed field.
  if stage!='r09a':flags+=('root_executed',)
  need(all(doc.get(k) is False for k in flags),'STAGE_UNSAFE_SCOPE '+stage)
  need(isinstance(doc.get('schema'),str) and doc['schema'].startswith('xbs-r09'),
       'STAGE_SCHEMA_DRIFT '+stage)
  ledger[stage]={'filename':filename,'sha256':hashlib.sha256(b).hexdigest(),
                 'size_bytes':len(b),'schema':doc['schema']}
 return ledger

def coverage_evidence(data_bytes,sha,run_id,attempt):
 need('r09d-risk.coverprofile' in data_bytes,'MISSING_FULL_COVERPROFILE')
 raw=data_bytes['r09d-risk.coverprofile']
 need(type(raw) is bytes and len(raw)>100,'COVERPROFILE_EMPTY')
 r09d=json.loads(data_bytes[STAGES['r09d']]);r09e=json.loads(data_bytes[STAGES['r09e']])
 rd=load_companion('r09d_risk_coverage.py')
 re=load_companion('r09e_boundary_coverage.py')
 rd.validate_receipt(r09d,sha,run_id,attempt,raw)
 re.validate_receipt(r09e,sha,run_id,attempt,r09d,raw)
 value=r09d.get('all_internal_coverage_pct')
 need(type(value) in (int,float) and value>=BASELINE_PCT,'FULL_COVERAGE_REGRESSION')
 need(r09d.get('r09_full_coverage_gate_qualified') is True and r09d.get('unqualified_risk_targets')==[],
      'SEVEN_PACKAGE_FLOOR_NOT_QUALIFIED')
 need(r09e.get('coverage',{}).get('current_full_pct')==value,'R09E_COVERAGE_DISAGREES_WITH_R09D')
 return {'full_internal_pct':value,'nonregression_floor_pct':BASELINE_PCT,
         'delta_over_r09e_pp':round(value-BASELINE_PCT,2),
         'profile_sha256':hashlib.sha256(raw).hexdigest(),
         'seven_package_floor_qualified':True,
         'risk_package_percentages':r09e['coverage']['risk_package_percentages']}

def validate_receipt(receipt,sha,run_id,attempt,data_bytes):
 need(isinstance(receipt,dict) and receipt.get('schema')==SCHEMA,'SCHEMA_DRIFT')
 need(receipt.get('xbs_source_sha')==sha and receipt.get('github_run_id')==str(run_id) and
      receipt.get('github_run_attempt')==str(attempt),'LEDGER_SOURCE_RUN_ATTEMPT_DRIFT')
 expected=required_ids();children=subtest_ids()
 need(receipt.get('required_test_ids')==expected and receipt.get('required_subtest_ids')==children,
      'NAMED_ROOT_OR_SUBTEST_INVENTORY_DRIFT')
 for key,want in [('root_tests',6),('subtests',88),('root_pass_events',6),('subtest_pass_events',88),
                  ('repeat_root_pass_events',18),('repeat_subtest_pass_events',264),
                  ('race_root_pass_events',6),('race_subtest_pass_events',88),
                  ('passed_packages',2),('race_packages',2),('failed_events',0),('skipped_events',0)]:
  need(type(receipt.get(key)) is int and receipt[key]==want,'GO_TEST_COUNT_DRIFT '+key)
 need(receipt.get('phase_ledger')==phase_evidence(data_bytes,sha,run_id,attempt),'PHASE_LEDGER_DRIFT')
 need(receipt.get('coverage')==coverage_evidence(data_bytes,sha,run_id,attempt),'COVERAGE_LEDGER_DRIFT')
 need(all(receipt.get(k) is False for k in ('root_executed','device_mutated','physical_root_qualified','release_qualified')),
      'FALSE_PHYSICAL_OR_RELEASE_QUALIFICATION')
 return {'tests':6,'subtests':88,'repeat_roots':18,'repeat_subtests':264,'race_roots':6,'race_subtests':88,
         'full_coverage_pct':receipt['coverage']['full_internal_pct'],'phase_count':5}

def hosted(repo):
 need(os.environ.get('GITHUB_ACTIONS')=='true' and os.environ.get('GITHUB_EVENT_NAME') in ('pull_request','push','workflow_dispatch'),
      'NOT_HOSTED_GITHUB')
 need(os.environ.get('XBS_ROOT_IT_ACK') is None,'ROOT_ACK_FORBIDDEN')
 if hasattr(os,'geteuid'):need(os.geteuid()!=0,'HOSTED_ROOT_FORBIDDEN')
 sha=invoke(['git','rev-parse','HEAD'],repo).strip()
 need(re.fullmatch('[a-f0-9]{40}',sha) is not None and sha==os.environ.get('XBS_EXPECTED_HEAD_SHA'),
      'EXACT_CHECKOUT_SHA_DRIFT')
 runid=os.environ.get('GITHUB_RUN_ID','');attempt=os.environ.get('GITHUB_RUN_ATTEMPT','')
 need(runid.isdecimal() and attempt.isdecimal() and int(runid)>0 and int(attempt)>0,'RUN_IDENTITY_MISSING')
 folder=repo/'dist';need(folder.is_dir() and not folder.is_symlink(),'UNSAFE_EVIDENCE_FOLDER')
 receipt=folder/'r09f-final-ledger.json';need(not receipt.exists() and not receipt.is_symlink(),'STALE_FINAL_LEDGER')
 stage_bytes={name:(folder/name).read_bytes() for name in (*STAGES.values(),'r09d-risk.coverprofile')}
 # Validate previous, same-run receipts BEFORE launching any new tests.
 coverage=coverage_evidence(stage_bytes,sha,int(runid),int(attempt))
 phases=phase_evidence(stage_bytes,sha,int(runid),int(attempt))
 baseline=parse_test_events(invoke(['go','test','-json','-count=1','-run','^TestR09F',*PACKAGES],repo,420))
 repeated=parse_test_events(invoke(['go','test','-json','-count=3','-run','^TestR09F',*PACKAGES],repo,580),3)
 raced=parse_test_events(invoke(['go','test','-race','-json','-count=1','-run','^TestR09F',*PACKAGES],repo,600))
 data={**baseline,'schema':SCHEMA,'xbs_source_sha':sha,'github_run_id':runid,
       'github_run_attempt':attempt,'repeat_root_pass_events':repeated['root_pass_events'],
       'repeat_subtest_pass_events':repeated['subtest_pass_events'],
       'race_root_pass_events':raced['root_pass_events'],
       'race_subtest_pass_events':raced['subtest_pass_events'],
       'race_packages':raced['passed_packages'],'phase_ledger':phases,'coverage':coverage,
       'root_executed':False,'device_mutated':False,'physical_root_qualified':False,'release_qualified':False}
 validate_receipt(data,sha,int(runid),int(attempt),stage_bytes)
 receipt.write_text(json.dumps(data,indent=2,sort_keys=True)+'\n')
 print('XBS_R09F_ADVERSARIAL_PASS tests=6 subtests=88 repeat=18 repeat_subtests=264 race=6 race_subtests=88 coverage='+str(coverage['full_internal_pct'])+' stages=5',flush=True)
 print('XBS_R09F_FINAL_LEDGER_SHA256 '+hashlib.sha256(receipt.read_bytes()).hexdigest(),flush=True)

def main():
 p=argparse.ArgumentParser();p.add_argument('--hosted',action='store_true');p.add_argument('--repo',type=Path,default=Path.cwd());a=p.parse_args()
 try:need(a.hosted,'HOSTED_ONLY');hosted(a.repo.resolve())
 except (Refused,OSError,ValueError,TypeError) as e:
  print('XBS_R09F_REFUSED '+str(e),file=sys.stderr);raise SystemExit(2)
if __name__=='__main__':main()
