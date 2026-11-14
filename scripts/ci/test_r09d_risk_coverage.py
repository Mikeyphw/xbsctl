#!/usr/bin/env python3
"""R09D negative tests for Go counts and coverage bytes; no network needed."""
from __future__ import annotations
import copy
import importlib.util
import json
from pathlib import Path
import unittest
import hashlib

ROOT=Path(__file__).resolve().parents[2]
P=Path(__file__).with_name('r09d_risk_coverage.py')
spec=importlib.util.spec_from_file_location('risk',P)
M=importlib.util.module_from_spec(spec);spec.loader.exec_module(M)
SHA='a'*40
PREFIX=M.PREFIX

def go_events(repetitions=1):
 items=[]
 for pkg,tests in M.GO_TESTS.items():
  for test in sorted(tests):
   for _ in range(repetitions):items.append({'Action':'pass','Package':PREFIX+pkg,'Test':test})
  items.append({'Action':'pass','Package':PREFIX+pkg})
 return '\n'.join(map(json.dumps,items))+'\n'

def cov(covered=None):
 covered=covered or {x:1 for x in M.RISK}
 lines=['mode: atomic']
 for p in M.RISK:
  lines.append(f'{PREFIX}{p}/{p}.go:1.1,2.1 4 {covered[p]}')
 return ('\n'.join(lines)+'\n').encode()

def valid():
 data=M.parse_events(go_events())
 profile=cov()
 data.update({'schema':M.SCHEMA,'xbs_source_sha':SHA,'github_run_id':'123','github_run_attempt':'2',
  'repeat_root_events':3*data['required_root_tests'],'race_root_events':data['required_root_tests'],
  'race_packages':2,'full_go_suite':{'total_root_test_pass_events':25,
  'risk_package_root_cases':{p:2 for p in M.RISK},'total_passed_internal_packages':8,
  'allowed_hosted_skip_tests':sorted(p+'::'+t for p,t in M.EXPECTED_HOSTED_SKIPS),
  'allowed_hosted_skips':2,'unexpected_skipped_cases':0,'failed_cases':0,
  'no_test_package_results':{'machine':'pass','version':'skip'},'no_test_package_skip_count':1},
  'coverage_profile_sha256':hashlib.sha256(profile).hexdigest(),**M.parse_coverprofile(profile),
  'root_executed':False,'device_mutated':False,'physical_root_qualified':False,'release_qualified':False})
 return data,profile

class CoverageTests(unittest.TestCase):
 def test_required_ids_and_three_repetitions(self):
  self.assertEqual(M.parse_events(go_events())['required_root_tests'],6)
  self.assertEqual(M.parse_events(go_events(3),3)['passed_root_events'],18)
 def test_missing_case_refused(self):
  s=go_events();case='TestR09DPostEffectCrashRecoveryMatrix'
  with self.assertRaises(M.Refused):M.parse_events('\n'.join(x for x in s.splitlines() if case not in x))
 def test_duplicate_root_and_wrong_repetition_refused(self):
  with self.assertRaises(M.Refused):M.parse_events(go_events(3))
  with self.assertRaises(M.Refused):M.parse_events(go_events(),3)
 def test_skipped_failed_and_malformed_go_refuse(self):
  for action in ('skip','fail'):
   with self.subTest(action),self.assertRaises(M.Refused):M.parse_events(go_events()+json.dumps({'Action':action,'Package':PREFIX+'tuning','Test':'x'}))
  with self.assertRaises(M.Refused):M.parse_events(go_events()+'invalid\n')
 def test_coverage_accepts_real_signed_internal_blocks(self):
  self.assertEqual(M.parse_coverprofile(cov())['all_internal_coverage_pct'],100.0)
  self.assertFalse(M.parse_coverprofile(cov())['unqualified_risk_targets'])
 def test_coverage_missing_package_zero_and_bad_data_refuse(self):
  for bad in [cov().replace((PREFIX+'cli/cli.go').encode(),(PREFIX+'other/cli.go').encode()),
    cov({p:(0 if p=='cli' else 1) for p in M.RISK}),cov().replace(b'mode: atomic',b'mode: set'),cov()+b'bad-line\n']:
   with self.subTest(bad=bad[:50]),self.assertRaises(M.Refused):M.parse_coverprofile(bad)
 def test_below_target_is_honest_gap(self):
  sample=cov({p:(0 if p=='cli' else 1) for p in M.RISK})
  # With zero cli statements executed this cannot claim any coverage at all.
  with self.assertRaises(M.Refused):M.parse_coverprofile(sample)
  sample='mode: atomic\n'+''.join(f'{PREFIX}{p}/{p}.go:1.1,2.1 10 1\n{PREFIX}{p}/{p}.go:3.1,4.1 90 0\n' for p in M.RISK)
  record=M.parse_coverprofile(sample.encode())
  self.assertEqual(len(record['unqualified_risk_targets']),7)
  self.assertFalse(record['r09_full_coverage_gate_qualified'])
 def test_receipt_binds_source_attempt_test_ids_and_real_profile(self):
  data,profile=valid()
  self.assertEqual(M.validate_receipt(data,SHA,123,2,profile)['named_tests'],6)
  for patch in ({'xbs_source_sha':'b'*40},{'github_run_attempt':'1'},
   {'required_test_ids':[]},{'repeat_root_events':3},{'race_root_events':0},
   {'coverage_profile_sha256':'b'*64},{'root_executed':True},
   {'unqualified_risk_targets':['wrong']},{'all_internal_coverage_pct':0},
   {'full_go_suite':{'risk_package_root_cases':{}}},
   {'full_go_suite':{**data['full_go_suite'],'allowed_hosted_skips':0}},
   {'full_go_suite':{**data['full_go_suite'],'unexpected_skipped_cases':1}},
   {'full_go_suite':{**data['full_go_suite'],'no_test_package_skip_count':0}},
   {'full_go_suite':{**data['full_go_suite'],'no_test_package_results':{'version':'pass'}}}):
   with self.subTest(patch=patch),self.assertRaises(M.Refused):M.validate_receipt({**data,**patch},SHA,123,2,profile)
 def test_real_go_sources_nonroot_and_unique_case_inventory(self):
  tests={'tuning':ROOT/'internal/tuning/r09d_crash_recovery_test.go',
         'control':ROOT/'internal/control/r09d_crash_queue_test.go'}
  for p,f in tests.items():
   self.assertTrue(f.is_file(),p)
   content=f.read_text()
   self.assertIn('package '+p,content)
   for name in M.GO_TESTS[p]:self.assertEqual(content.count('func '+name+'('),1)
   for prohibited in ('os/exec','exec.Command(', 'SystemRunner{}','su -c ','adb shell','ksud module install'):
    self.assertNotIn(prohibited,content)
 def test_real_full_go_parser_accepts_only_two_expected_hosted_skips(self):
  items=[]
  for p in M.RISK:
   package=M.PREFIX+p
   items.append({'Action':'pass','Package':package,'Test':'TestExample'+p})
   items.append({'Action':'pass','Package':package})
  for (pkg,case),reason in M.EXPECTED_HOSTED_SKIPS.items():
   items.insert(0,{'Action':'output','Package':pkg,'Test':case,'Output':reason+'\n'})
   items.insert(1,{'Action':'skip','Package':pkg,'Test':case})
  items.extend([{'Action':'pass','Package':M.PREFIX+'machine'},
                {'Action':'output','Package':M.PREFIX+'version',
                 'Output':'?   \t'+M.PREFIX+'version\t[no test files]\n'},
                {'Action':'skip','Package':M.PREFIX+'version'}])
  raw='\n'.join(map(json.dumps,items))+'\n'
  got=M.parse_full_suite(raw)
  self.assertEqual(got['allowed_hosted_skips'],2)
  self.assertEqual(got['unexpected_skipped_cases'],0)
  self.assertEqual(len(got['allowed_hosted_skip_tests']),2)
  self.assertEqual(got['total_root_test_pass_events'],7)
 def test_real_full_go_parser_refuses_malformed_or_unexpected_skips(self):
  base=[]
  for p in M.RISK:
   pkg=M.PREFIX+p
   base.extend([{'Action':'pass','Package':pkg,'Test':'TestExample'+p},
                {'Action':'pass','Package':pkg}])
  for (pkg,case),reason in M.EXPECTED_HOSTED_SKIPS.items():
   base.extend([{'Action':'output','Package':pkg,'Test':case,'Output':reason+'\n'},
                {'Action':'skip','Package':pkg,'Test':case}])
  base.extend([{'Action':'pass','Package':M.PREFIX+'machine'},
               {'Action':'output','Package':M.PREFIX+'version',
                'Output':'?   \t'+M.PREFIX+'version\t[no test files]\n'},
               {'Action':'skip','Package':M.PREFIX+'version'}])
  for extra in (
   {'Action':'skip','Package':M.PREFIX+'cli','Test':'TestExamplecli'},
   {'Action':'fail','Package':M.PREFIX+'control','Test':'TestUnexpected'},
   {'Action':'skip','Package':M.PREFIX+'control','Test':'TestR09DQueueClaimAckCrashMatrix'},
  ):
   with self.subTest(extra=extra),self.assertRaises(M.Refused):
    M.parse_full_suite('\n'.join(map(json.dumps,base+[extra]))+'\n')
  # Wrong environment explanation, a missing expected skip, and a duplicate
  # guarded skip all refuse even when all root tests otherwise pass.
  for variant in (base[:-1], base+[base[-1]],
    [{**v,'Output':'not a recognized environmental reason'} if v.get('Action')=='output' else v for v in base],
    base+[{'Action':'skip','Package':M.PREFIX+'daemon'}],
    base+[{'Action':'skip','Package':M.PREFIX+'version'}],
    [x for x in base if x.get('Package')!=M.PREFIX+'version'],
    [{**x,'Output':'?  \t'+M.PREFIX+'version\tpermission denied\n'}
     if x.get('Action')=='output' and x.get('Package')==M.PREFIX+'version' else x for x in base]):
   with self.subTest(variant=str(variant[-1])[:60]),self.assertRaises(M.Refused):
    M.parse_full_suite('\n'.join(map(json.dumps,variant))+'\n')
 def test_no_test_package_skip_is_not_a_skipped_test(self):
  base=[]
  for p in M.RISK:
   pkg=M.PREFIX+p
   base.extend([{'Action':'pass','Package':pkg,'Test':'TestExample'+p},
                {'Action':'pass','Package':pkg}])
  for (pkg,case),reason in M.EXPECTED_HOSTED_SKIPS.items():
   base.extend([{'Action':'output','Package':pkg,'Test':case,'Output':reason+'\n'},
                {'Action':'skip','Package':pkg,'Test':case}])
  def decode(rows):return '\n'.join(map(json.dumps,rows))+'\n'
  for machine_skip,version_skip in [(False,False),(False,True),(True,False),(True,True)]:
   rows=list(base)
   for pkg,skip in [('machine',machine_skip),('version',version_skip)]:
    identifier=M.PREFIX+pkg
    if skip:
     rows.extend([{'Action':'output','Package':identifier,
                   'Output':'?  \t'+identifier+'\t[no test files]\n'},
                  {'Action':'skip','Package':identifier}])
    else:rows.append({'Action':'pass','Package':identifier})
   got=M.parse_full_suite(decode(rows))
   self.assertEqual(got['no_test_package_skip_count'],int(machine_skip)+int(version_skip))
   self.assertEqual(got['no_test_package_results']['machine'],'skip' if machine_skip else 'pass')
   for mutant in (
    rows+[{'Action':'skip','Package':M.PREFIX+'module'}],
    rows+[{'Action':'skip','Package':M.PREFIX+'machine','Test':'TestFake'}],
    rows+[{'Action':'fail','Package':M.PREFIX+'version'}],
    rows+[{'Action':'pass','Package':M.PREFIX+'version','Test':'TestUnexpected'}]):
    with self.assertRaises(M.Refused):M.parse_full_suite(decode(mutant))
 def test_no_test_source_inventory_is_exact(self):
  self.assertEqual(M.NO_TEST_PACKAGES,{M.PREFIX+'machine',M.PREFIX+'version'})
  for name in ('machine','version'):
   folder=ROOT/'internal'/name
   self.assertTrue(folder.is_dir())
   self.assertTrue(list(folder.glob('*.go')))
   self.assertFalse(list(folder.glob('*_test.go')))
 def test_hosted_skip_guards_still_exist_in_real_go_sources(self):
  cases=[(ROOT/'internal/control/spool_ownership_test.go',
          'TestR02AuditedSpoolRejectsForeignOwnerWhenSupported',
          'test requires ability to create a foreign-owned directory'),
         (ROOT/'internal/tuning/root_integration_test.go',
          'TestRootIntegrationEffectFamilies',
          'set XBS_ROOT_IT_ACK=effects-are-reversible on a disposable rooted Android test device')]
  for source,test,reason in cases:
   s=source.read_text()
   self.assertEqual(s.count('func '+test+'('),1)
   self.assertIn(reason,s)
  self.assertNotIn('XBS_ROOT_IT_ACK=',(ROOT/'.github/workflows/devtool-xbs-ci.yml').read_text())
 def test_workflow_real_required_lane(self):
  w=(ROOT/'.github/workflows/devtool-xbs-ci.yml').read_text()
  for needed in ('R09D crash recovery and complete risk coverage inventory',
                 'r09d_risk_coverage.py --hosted','dist/r09d-risk-coverage.json',
                 'dist/r09d-risk.coverprofile','R09C fake Android backend and CLI golden contracts',
                 'needs: [go-contract, android-companion-jvm]','connectedDebugAndroidTest'):
   self.assertIn(needed,w)

if __name__=='__main__':unittest.main(verbosity=2)
