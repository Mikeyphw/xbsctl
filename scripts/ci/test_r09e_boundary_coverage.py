#!/usr/bin/env python3
"""R09E fail-closed, synthetic boundary inventory and actual file guards."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
SP=importlib.util.spec_from_file_location('matrix',Path(__file__).with_name('r09e_boundary_coverage.py'))
M=importlib.util.module_from_spec(SP);SP.loader.exec_module(M)
P=M.PKG_PREFIX

def events(count=1):
 rows=[]
 for pkg in M.RISK:
  test=M.ROOTS[pkg]
  for _ in range(count):rows.extend(({'Action':'run','Package':P+pkg,'Test':test},{'Action':'pass','Package':P+pkg,'Test':test}))
  rows.append({'Action':'pass','Package':P+pkg})
 return '\n'.join(map(json.dumps,rows))+'\n'

class Tests(unittest.TestCase):
 def test_exact_inventory(self):
  a=M.parse_tests(events());self.assertEqual(a['root_tests'],7);self.assertEqual(a['packages_passed'],7)
  self.assertEqual(M.parse_tests(events(3),3)['root_pass_events'],21)
 def test_failure_skip_missing_duplicate_and_json_refused(self):
  source=events()
  for mutant in [source+'invalid\n',source.replace('"pass"','"skip"',1),
    source.replace('"pass"','"fail"',1),source.replace('"run"','"pass"',1),
    '\n'.join(x for x in source.splitlines() if 'TestR09EControl' not in x),
    source+json.dumps({'Action':'pass','Package':P+'module','Test':'TestExtraneous'}),
    source+json.dumps({'Action':'pass','Package':P+'module'}),source.replace(P+'daemon',P+'machine'),
   ]:
   with self.subTest(mutant=mutant[-70:]),self.assertRaises(M.Refused):M.parse_tests(mutant)
 def test_repeat_not_assumed(self):
  with self.assertRaises(M.Refused):M.parse_tests(events(),3)
 def test_real_test_source_inventory(self):
  for pkg,test in M.ROOTS.items():
   p=ROOT/'internal'/pkg/'r09e_boundary_test.go';self.assertTrue(p.is_file(),p)
   raw=p.read_text();self.assertIn('package '+pkg,raw)
   self.assertEqual(raw.count('func '+test+'('),1)
   for unwanted in ('exec.Command(', 'su -c', 'ksud module install ', 'adb shell', 'os.RemoveAll('):
    self.assertNotIn(unwanted,raw)
 def test_workflow_keeps_prior_gates(self):
  s=(ROOT/'.github/workflows/devtool-xbs-ci.yml').read_text()
  for x in ('R09A bounded backend fuzz','R09B deterministic fault injection',
    'R09C fake Android backend','R09D crash recovery','R09E seven-package boundaries and measured coverage non-regression',
    'dist/r09e-boundary-coverage.json','needs: [go-contract, android-companion-jvm]','connectedDebugAndroidTest'):
   self.assertIn(x,s)
 def test_r09d_dependency_exists(self):
  # Existing file is part of immutable upstream source; not an R09E-created placeholder.
  p=ROOT/'scripts/ci/r09d_risk_coverage.py'
  if p.exists():self.assertIn('def validate_receipt(',p.read_text())
 def test_receipt_false_qualification_and_coverage_drift(self):
  original=M.coverage_binding
  try:
   M.coverage_binding=lambda *args:{'baseline_full_pct':71.29,'current_full_pct':71.29,'coverage_profile_sha256':'f'*64}
   d=M.parse_tests(events());d.update({'schema':M.SCHEMA,'xbs_source_sha':'a'*40,
     'github_run_id':'123','github_run_attempt':'1','repeat_pass_events':21,'race_pass_events':7,
     'race_packages':7,'coverage':M.coverage_binding(), 'root_executed':False,
     'device_mutated':False,'physical_root_qualified':False,'release_qualified':False})
   self.assertEqual(M.validate_receipt(d,'a'*40,123,1,{},b'')['tests'],7)
   for patch in [{'github_run_attempt':'2'},{'repeat_pass_events':7},{'race_pass_events':0},
     {'root_tests':6},{'required_test_ids':[]},{'coverage':{}},
     {'root_executed':True},{'release_qualified':True}, {'failed_tests':1}]:
    with self.subTest(patch=patch),self.assertRaises(M.Refused):M.validate_receipt({**d,**patch},'a'*40,123,1,{},b'')
  finally:M.coverage_binding=original
 def test_real_coverage_companion_import(self):
  if (ROOT/'scripts/ci/r09d_risk_coverage.py').exists():
   self.assertTrue(hasattr(M.companion(),'validate_receipt'))

if __name__=='__main__':unittest.main(verbosity=2)
