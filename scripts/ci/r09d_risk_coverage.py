#!/usr/bin/env python3
"""R09D hosted Go crash-point and full internal-package risk coverage evidence.

Only hosted GitHub execution can qualify test counts/coverage. Does not call
su, adb, Magisk, ksud, Gradle or modify real Android state. All evidence is
bound to checkout SHA, workflow run ID, attempt and actual coverprofile bytes.
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

SCHEMA='xbs-r09d-risk-coverage/v1'
PREFIX='github.com/Mikeyphw/xbsctl/internal/'
GO_TESTS={
 'tuning':{'TestR09DPostEffectCrashRecoveryMatrix','TestR09DRestoreFailureRetainsJournal',
   'TestR09DSnapshotCopyIsolation','TestR09DJournalReleaseAfterRestoreFailure'},
 'control':{'TestR09DQueueClaimAckCrashMatrix','TestR09DImmutableAckRejectsCompetingCompletion'},
}
RISK=('cli','config','control','daemon','eventbridge','module','tuning')
# Aspirational R09 FINAL coverage targets; this slice reports gaps but DOES
# NOT label the whole phase complete or conflate 19.9% selected coverage.
TARGET_PCT=50.0
# These are *only* the two known environment-gated skips on unprivileged
# hosted Linux. Their diagnostic reasons must appear in actual go test -json
# output; neither is counted as an executed test or physical-root evidence.
EXPECTED_HOSTED_SKIPS={
 (PREFIX+'control','TestR02AuditedSpoolRejectsForeignOwnerWhenSupported'):
  'test requires ability to create a foreign-owned directory',
 (PREFIX+'tuning','TestRootIntegrationEffectFamilies'):
  'set XBS_ROOT_IT_ACK=effects-are-reversible on a disposable rooted Android test device',
}
class Refused(RuntimeError):pass
def need(ok,why):
 if not ok:raise Refused(why)
def command(args,repo,timeout=360):
 try:p=subprocess.run(args,cwd=repo,text=True,errors='replace',capture_output=True,timeout=timeout)
 except (OSError,subprocess.TimeoutExpired) as e:raise Refused('COMMAND_UNAVAILABLE_OR_TIMEOUT '+repr(args[:5])) from e
 if p.returncode:
  raise Refused('COMMAND_FAILED rc='+str(p.returncode)+' '+repr(args[:6])+'\n'+'\n'.join((p.stdout+'\n'+p.stderr).splitlines()[-30:])[-6000:])
 return p.stdout

def parse_events(raw, repetitions=1, required=GO_TESTS):
 roots={};packages={};bad=[]
 for i,line in enumerate(raw.splitlines(),1):
  if not line.strip():continue
  try:ev=json.loads(line)
  except ValueError as e:raise Refused('GO_JSON_INVALID_LINE '+str(i)) from e
  need(isinstance(ev,dict),'GO_JSON_NONOBJECT_LINE_'+str(i))
  act=ev.get('Action');pkg=ev.get('Package');test=ev.get('Test')
  if act in ('fail','skip'):bad.append((str(pkg),str(test),act))
  if act=='pass' and isinstance(pkg,str):
   if isinstance(test,str) and test and '/' not in test:
    roots[(pkg,test)]=roots.get((pkg,test),0)+1
   elif not test:packages[pkg]=packages.get(pkg,0)+1
 need(not bad,'GO_FAILED_OR_SKIPPED '+repr(bad[:5]))
 expected={(PREFIX+p,t) for p,names in required.items() for t in names}
 need(set(roots)==expected,'GO_ROOT_TEST_INVENTORY_DRIFT '+repr(sorted(set(roots)^expected)))
 need(all(v==repetitions for v in roots.values()),'GO_ROOT_REPETITION_DRIFT '+repr(roots))
 need(set(packages)=={PREFIX+p for p in required},'GO_PACKAGE_INVENTORY_DRIFT '+repr(packages))
 need(all(v==1 for v in packages.values()),'GO_PACKAGE_DUPLICATE')
 return {'required_root_tests':len(expected),'passed_root_events':sum(roots.values()),
         'required_test_ids':sorted(pkg+'::'+case for pkg,case in expected),
         'passed_packages':len(packages)}

def parse_full_suite(raw):
 """Require full non-root test coverage, preserving the *exact* two intentional hosted skips.

 No unexpected skip, failed test, empty package, duplicate required skip, or
 renamed/missing environmental guard is accepted. Go event `output` must
 contain each expected skip's actual t.Skip diagnostic; synthetic skip events
 with the same name but a different reason are refused.
 """
 roots={};packages={};fail=[];observed_skips={};outputs={}
 for i,line in enumerate(raw.splitlines(),1):
  if not line.strip():continue
  try:x=json.loads(line)
  except ValueError as e:raise Refused('FULL_GO_BAD_JSON_LINE '+str(i)) from e
  need(isinstance(x,dict),'FULL_GO_NONOBJECT')
  act=x.get('Action');pkg=x.get('Package');case=x.get('Test')
  key=(pkg,case)
  if act=='output' and key in EXPECTED_HOSTED_SKIPS:
   outputs[key]=outputs.get(key,'')+str(x.get('Output',''))
  if act=='fail':fail.append((pkg,case,'fail'))
  if act=='skip':
   if key not in EXPECTED_HOSTED_SKIPS:
    fail.append((pkg,case,'unexpected-skip'))
   else:
    observed_skips[key]=observed_skips.get(key,0)+1
  if not isinstance(pkg,str) or not pkg.startswith(PREFIX):continue
  if act=='pass':
   if isinstance(case,str) and '/' not in case and case:
    roots[pkg]=roots.get(pkg,0)+1
   elif not case:packages[pkg]=packages.get(pkg,0)+1
 need(not fail,'FULL_GO_FAILURE_OR_UNEXPECTED_SKIP '+repr(fail[:10]))
 need(set(observed_skips)==set(EXPECTED_HOSTED_SKIPS),'HOSTED_SKIP_INVENTORY_DRIFT '+
      repr(sorted(set(observed_skips)^set(EXPECTED_HOSTED_SKIPS))))
 need(all(n==1 for n in observed_skips.values()),'HOSTED_SKIP_DUPLICATE '+repr(observed_skips))
 for key,reason in EXPECTED_HOSTED_SKIPS.items():
  need(reason in outputs.get(key,''),'HOSTED_SKIP_REASON_MISSING '+repr(key))
 required={PREFIX+p for p in RISK}
 need(required<=set(packages),'FULL_GO_PACKAGE_MISSING '+repr(sorted(required-set(packages))))
 need(all(roots.get(pkg,0)>0 for pkg in required),'FULL_GO_UNTESTED_RISK_PACKAGE '+repr(roots))
 return {'total_root_test_pass_events':sum(roots.values()),
         'risk_package_root_cases':{p:roots[PREFIX+p] for p in RISK},
         'total_passed_internal_packages':len(packages),
         'allowed_hosted_skip_tests':sorted(pkg+'::'+case for pkg,case in observed_skips),
         'allowed_hosted_skips':len(observed_skips),
         'unexpected_skipped_cases':0,
         'failed_cases':0}

def parse_coverprofile(raw:bytes):
 try:text=raw.decode('utf-8')
 except UnicodeDecodeError as e:raise Refused('COVERPROFILE_ENCODING') from e
 lines=text.splitlines();need(len(lines)>1 and lines[0]=='mode: atomic','COVERPROFILE_MODE_MISSING')
 stats={};duplicates=set()
 for i,line in enumerate(lines[1:],2):
  m=re.fullmatch(r'(?P<file>\S+):\d+\.\d+,\d+\.\d+\s+(?P<statements>\d+)\s+(?P<count>\d+)',line)
  need(bool(m),'COVERPROFILE_CORRUPT_LINE '+str(i))
  path=m.group('file')
  need(path.startswith(PREFIX),'COVERPROFILE_UNEXPECTED_PACKAGE '+path)
  need(line not in duplicates,'COVERPROFILE_DUPLICATE_BLOCK')
  duplicates.add(line)
  bucket=path[len(PREFIX):].split('/',1)[0]
  need(bool(bucket),'COVERPROFILE_EMPTY_PACKAGE')
  v=stats.setdefault(bucket,{'total_statements':0,'covered_statements':0})
  n=int(m.group('statements'));count=int(m.group('count'))
  need(n>=0 and count>=0,'COVERPROFILE_INVALID_COUNTER')
  v['total_statements']+=n
  if count:v['covered_statements']+=n
 required=set(RISK)
 need(required<=set(stats),'COVERPROFILE_REQUIRED_PACKAGES_ABSENT '+repr(sorted(required-set(stats))))
 evidence={}
 for p in RISK:
  v=stats[p]
  need(v['total_statements']>0 and v['covered_statements']>0,'RISK_PACKAGE_WITH_ZERO_COVERAGE '+p)
  pct=round(100*v['covered_statements']/v['total_statements'],2)
  evidence[p]={**v,'coverage_pct':pct,'target_pct':TARGET_PCT,
               'below_target':pct<TARGET_PCT}
 n=sum(v['total_statements'] for v in stats.values())
 covered=sum(v['covered_statements'] for v in stats.values())
 need(n>=covered>0,'TOTAL_RISK_COVERAGE_ZERO')
 return {'risk_packages':evidence,'all_internal_covered_statements':covered,
         'all_internal_statements':n,'all_internal_coverage_pct':round(100*covered/n,2),
         'unqualified_risk_targets':sorted(p for p,v in evidence.items() if v['below_target']),
         'r09_full_coverage_gate_qualified':all(not x['below_target'] for x in evidence.values())}

def validate_receipt(data,sha,run_id,attempt,profile_bytes):
 need(isinstance(data,dict) and data.get('schema')==SCHEMA,'R09D_WRONG_SCHEMA')
 need(data.get('xbs_source_sha')==sha and data.get('github_run_id')==str(run_id) and
      data.get('github_run_attempt')==str(attempt),'R09D_SOURCE_RUN_ATTEMPT_DRIFT')
 expected=sorted(PREFIX+p+'::'+t for p,ts in GO_TESTS.items() for t in ts)
 need(data.get('required_test_ids')==expected and data.get('required_root_tests')==len(expected),'R09D_NAMED_TEST_IDS_DRIFT')
 need(data.get('passed_root_events')==len(expected) and data.get('repeat_root_events')==3*len(expected) and
      data.get('race_root_events')==len(expected),'R09D_REAL_REPETITION_OR_RACE_MISSING')
 need(data.get('passed_packages')==2 and data.get('race_packages')==2,'R09D_TEST_PACKAGES_DRIFT')
 need(data.get('coverage_profile_sha256')==hashlib.sha256(profile_bytes).hexdigest(),'R09D_COVERPROFILE_BYTES_DRIFT')
 actual=parse_coverprofile(profile_bytes)
 for k,v in actual.items():need(data.get(k)==v,'R09D_COVERAGE_METADATA_DRIFT '+k)
 counts=data.get('full_go_suite')
 need(isinstance(counts,dict),'R09D_FULL_SUITE_COUNTS_MISSING')
 need(set(counts.get('risk_package_root_cases',{}))==set(RISK) and
      all(type(v) is int and v>0 for v in counts['risk_package_root_cases'].values()),'R09D_RISK_CASE_COUNTS_INVALID')
 need(type(counts.get('total_root_test_pass_events')) is int and counts['total_root_test_pass_events']>=sum(counts['risk_package_root_cases'].values()),'R09D_TOTAL_TEST_COUNT_INVALID')
 expected_skips=sorted(pkg+'::'+case for pkg,case in EXPECTED_HOSTED_SKIPS)
 need(counts.get('allowed_hosted_skip_tests')==expected_skips and
      type(counts.get('allowed_hosted_skips')) is int and counts['allowed_hosted_skips']==len(expected_skips),
      'R09D_EXPECTED_HOSTED_SKIP_RECEIPT_DRIFT')
 need(type(counts.get('unexpected_skipped_cases')) is int and counts['unexpected_skipped_cases']==0 and
      type(counts.get('failed_cases')) is int and counts['failed_cases']==0,
      'R09D_FULL_SUITE_FAILED_OR_UNEXPECTED_SKIPS')
 need(all(data.get(k) is False for k in ('root_executed','device_mutated','physical_root_qualified','release_qualified')),'R09D_UNSAFE_QUALIFICATION_CLAIM')
 return {'named_tests':len(expected),'repeat':3*len(expected),'race':len(expected),
         'coverage_pct':actual['all_internal_coverage_pct'],
         'gap_packages':actual['unqualified_risk_targets'],'allowed_hosted_skips':counts['allowed_hosted_skips'],
  'r09_full_coverage_gate_qualified':actual['r09_full_coverage_gate_qualified']}

def hosted(repo:Path):
 need(os.environ.get('GITHUB_ACTIONS')=='true' and os.environ.get('GITHUB_EVENT_NAME') in ('pull_request','push','workflow_dispatch'),'NOT_HOSTED_GITHUB')
 need(os.environ.get('XBS_ROOT_IT_ACK') is None,'ROOT_ACK_FORBIDDEN')
 if hasattr(os,'geteuid'):need(os.geteuid()!=0,'HOSTED_ROOT_REFUSED')
 sha=command(['git','rev-parse','HEAD'],repo).strip()
 need(re.fullmatch('[a-f0-9]{40}',sha) is not None and sha==os.environ.get('XBS_EXPECTED_HEAD_SHA'),'CHECKOUT_SHA_MISMATCH')
 run=os.environ.get('GITHUB_RUN_ID','');attempt=os.environ.get('GITHUB_RUN_ATTEMPT','')
 need(run.isdecimal() and attempt.isdecimal() and int(run)>0 and int(attempt)>0,'RUN_ATTEMPT_MISSING')
 folder=repo/'dist'
 need(not folder.is_symlink(),'DIST_SYMLINK_REFUSED');folder.mkdir(parents=True,exist_ok=True)
 receipt=folder/'r09d-risk-coverage.json';cover=folder/'r09d-risk.coverprofile'
 need(not receipt.exists() and not cover.exists(),'R09D_STALE_OUTPUT_REFUSED')
 pkgs=['./internal/'+p for p in GO_TESTS]
 first=parse_events(command(['go','test','-json','-count=1','-run','^TestR09D',*pkgs],repo,timeout=450))
 repeat=parse_events(command(['go','test','-json','-count=3','-run','^TestR09D',*pkgs],repo,timeout=520),repetitions=3)
 raced=parse_events(command(['go','test','-race','-json','-count=1','-run','^TestR09D',*pkgs],repo,timeout=640))
 full=parse_full_suite(command(['go','test','-json','-count=1','-covermode=atomic',
  '-coverpkg=./internal/...','-coverprofile='+str(cover),'./internal/...'],repo,timeout=1000))
 data={**first,'schema':SCHEMA,'xbs_source_sha':sha,'github_run_id':run,'github_run_attempt':attempt,
       'repeat_root_events':repeat['passed_root_events'],'race_root_events':raced['passed_root_events'],
       'race_packages':raced['passed_packages'],'full_go_suite':full,
       'coverage_profile_sha256':hashlib.sha256(cover.read_bytes()).hexdigest(),
       **parse_coverprofile(cover.read_bytes()),
       'root_executed':False,'device_mutated':False,'physical_root_qualified':False,'release_qualified':False}
 validate_receipt(data,sha,int(run),int(attempt),cover.read_bytes())
 receipt.write_text(json.dumps(data,indent=2,sort_keys=True)+'\n')
 print('XBS_R09D_REAL_BACKEND_PASS tests='+str(len(first['required_test_ids']))+
  ' repeat='+str(repeat['passed_root_events'])+' race='+str(raced['passed_root_events'])+
  ' risk_packages='+str(len(RISK))+' total_coverage_pct='+str(data['all_internal_coverage_pct'])+
  ' below_target='+str(len(data['unqualified_risk_targets']))+
  ' hosted_skips='+str(full['allowed_hosted_skips']),flush=True)
 print('XBS_R09D_COVERAGE_LEDGER_SHA256 '+data['coverage_profile_sha256'],flush=True)

def main():
 p=argparse.ArgumentParser();p.add_argument('--hosted',action='store_true');p.add_argument('--repo',type=Path,default=Path.cwd());a=p.parse_args()
 try:
  need(a.hosted,'HOSTED_ONLY')
  hosted(a.repo.resolve())
 except Refused as e:
  print('XBS_R09D_REFUSED '+str(e),file=sys.stderr);raise SystemExit(2)
if __name__=='__main__':main()
