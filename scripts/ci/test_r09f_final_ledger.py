#!/usr/bin/env python3
"""R09F offline source-bound, risk-matrix and mutation regressions."""
from __future__ import annotations
import copy
import importlib.util
import json
from pathlib import Path
from unittest import mock
import unittest

SRC=Path(__file__).with_name('r09f_final_ledger.py')
spec=importlib.util.spec_from_file_location('r09f_policy_tested',SRC)
M=importlib.util.module_from_spec(spec);spec.loader.exec_module(M)
ROOT=Path(__file__).resolve().parents[2]
SHA='a'*40

def stream(reps=1):
 events=[]
 for pkg,names in M.ROOTS.items():
  p=M.PREFIX+pkg
  for name in names:
   for i in range(reps):
    events.append({'Action':'run','Package':p,'Test':name})
    for case in M.SUBTESTS[name]:
     events.extend([{'Action':'run','Package':p,'Test':name+'/'+case},
                    {'Action':'pass','Package':p,'Test':name+'/'+case}])
    events.append({'Action':'pass','Package':p,'Test':name})
  events.append({'Action':'pass','Package':p})
 return events

def bytes_of(rows):return '\n'.join(json.dumps(v) for v in rows)+'\n'

def stages():
 result={}
 for stage,name in M.STAGES.items():
  fields={'schema':f'xbs-{stage}-example/v1','xbs_source_sha':SHA,
          'github_run_id':'9','github_run_attempt':'2',
          'device_mutated':False,'physical_root_qualified':False,'release_qualified':False}
  if stage!='r09a':fields['root_executed']=False
  result[name]=json.dumps(fields).encode()+b' '*120
 return result

class R09FTests(unittest.TestCase):
 def test_exact_go_roots_subcases_and_repetition(self):
  got=M.parse_test_events(bytes_of(stream()))
  self.assertEqual((got['root_tests'],got['subtests'],got['root_pass_events'],got['subtest_pass_events']),(6,88,6,88))
  repeated=M.parse_test_events(bytes_of(stream(3)),3)
  self.assertEqual((repeated['root_pass_events'],repeated['subtest_pass_events']),(18,264))
 def test_go_count_failures_and_unexpected_extras(self):
  rows=stream()
  for mutant in [rows[:-1],rows+[rows[-1]],rows+[{'Action':'skip','Package':M.PREFIX+'control','Test':'TestR09FQueueRestartSchedule'}],
                 rows+[{'Action':'fail','Package':M.PREFIX+'control','Test':'TestR09FQueueRestartSchedule'}],
                 [v for v in rows if v.get('Test')!='TestR09FAckImmutabilitySchedule'],
                 [v for v in rows if v.get('Test')!='TestR09FQueueRestartSchedule/restart-00'],
                 rows+[{'Action':'pass','Package':M.PREFIX+'tuning','Test':'TestUnexpected'}]]:
   with self.subTest(rows=len(mutant)),self.assertRaises(M.Refused):M.parse_test_events(bytes_of(mutant))
  for bad in ('{broken\n','[]\n',''):
   with self.assertRaises(M.Refused):M.parse_test_events(bad)
 def test_all_phases_exact_run_same_source(self):
  good=stages();out=M.phase_evidence(good,SHA,9,2)
  self.assertEqual(set(out),set(M.STAGES))
  self.assertEqual(len(out['r09a']['sha256']),64)
  for stage,name in M.STAGES.items():
   base=json.loads(good[name]);mod=copy.deepcopy(good)
   base['github_run_attempt']='3';mod[name]=json.dumps(base).encode()+b' '*120
   with self.assertRaises(M.Refused):M.phase_evidence(mod,SHA,9,2)
   base=json.loads(good[name]);base['release_qualified']=True
   mod[name]=json.dumps(base).encode()+b' '*120
   with self.assertRaises(M.Refused):M.phase_evidence(mod,SHA,9,2)
  for name in M.STAGES.values():
   with self.assertRaises(M.Refused):M.phase_evidence({k:v for k,v in good.items() if k!=name},SHA,9,2)
 def test_r09a_legacy_missing_root_execution_field_only(self):
  x=stages();self.assertIn('r09a',M.phase_evidence(x,SHA,9,2))
  for key in ('r09b','r09c','r09d','r09e'):
   filename=M.STAGES[key];data=json.loads(x[filename]);del data['root_executed']
   altered={**x,filename:json.dumps(data).encode()+b' '*120}
   with self.assertRaises(M.Refused):M.phase_evidence(altered,SHA,9,2)
 def test_coverage_nonregression_and_profile_binding(self):
  x=stages();x['r09d-risk.coverprofile']=b'mode: atomic\n'+b'x'*200
  r09d={'all_internal_coverage_pct':72.07,'r09_full_coverage_gate_qualified':True,'unqualified_risk_targets':[]}
  r09e={'coverage':{'current_full_pct':72.07,'risk_package_percentages':{p:55.0 for p in ('cli','config','control','daemon','eventbridge','module','tuning')}}}
  x[M.STAGES['r09d']]=json.dumps(r09d).encode();x[M.STAGES['r09e']]=json.dumps(r09e).encode()
  pseudo=mock.Mock(validate_receipt=mock.Mock())
  with mock.patch.object(M,'load_companion',return_value=pseudo):
   self.assertEqual(M.coverage_evidence(x,SHA,9,2)['full_internal_pct'],72.07)
   for broken in [71.28,72.06]:
    data=dict(r09d,all_internal_coverage_pct=broken);x[M.STAGES['r09d']]=json.dumps(data).encode()
    with self.assertRaises(M.Refused):M.coverage_evidence(x,SHA,9,2)
   x[M.STAGES['r09d']]=json.dumps(r09d).encode()
   x[M.STAGES['r09e']]=json.dumps({'coverage':{'current_full_pct':73.0}}).encode()
   with self.assertRaises(M.Refused):M.coverage_evidence(x,SHA,9,2)
 def test_final_receipt_count_scope_and_hash_mutations(self):
  x=stages()
  row=M.parse_test_events(bytes_of(stream()))
  row.update({'schema':M.SCHEMA,'xbs_source_sha':SHA,'github_run_id':'9','github_run_attempt':'2',
              'repeat_root_pass_events':18,'repeat_subtest_pass_events':264,
              'race_root_pass_events':6,'race_subtest_pass_events':88,'race_packages':2,
              'root_executed':False,'device_mutated':False,'physical_root_qualified':False,'release_qualified':False})
  cov={'full_internal_pct':72.07,'seven_package_floor_qualified':True}
  row['phase_ledger']=M.phase_evidence(x,SHA,9,2);row['coverage']=cov
  with mock.patch.object(M,'coverage_evidence',return_value=cov):
   self.assertEqual(M.validate_receipt(row,SHA,9,2,x)['tests'],6)
   for change in ({'race_subtest_pass_events':87},{'root_pass_events':5},
                  {'required_subtest_ids':[]},{'phase_ledger':{}},
                  {'coverage':{'full_internal_pct':99}},{'root_executed':True},
                  {'xbs_source_sha':'b'*40},{'github_run_attempt':'3'}):
    with self.subTest(change=change),self.assertRaises(M.Refused):
     M.validate_receipt({**row,**change},SHA,9,2,x)
 def test_real_test_sources_not_android_root_commands(self):
  path_map={'control':ROOT/'internal/control/r09f_adversarial_schedule_test.go',
            'tuning':ROOT/'internal/tuning/r09f_adversarial_schedule_test.go'}
  for domain,p in path_map.items():
   data=p.read_text();self.assertIn('package '+domain,data)
   for name in M.ROOTS[domain]:self.assertEqual(data.count('func '+name+'('),1,name)
   for forbidden in ('exec.Command(', 'su -c ', 'adb shell ', 'ksud module install', 'SystemRunner{}'):
    self.assertNotIn(forbidden,data)
 def test_actual_workflow_contains_all_gates(self):
  source=(ROOT/'.github/workflows/devtool-xbs-ci.yml').read_text()
  for marker in ('R09F adversarial recovery and final hosted evidence ledger',
    'r09f_final_ledger.py --hosted','dist/r09f-final-ledger.json',
    'R09A bounded backend fuzz, repeat and coverage','R09B deterministic fault injection and zip negatives',
    'R09C fake Android backend and CLI golden contracts',
    'R09D crash recovery and complete risk coverage inventory',
    'R09E seven-package boundaries and measured coverage non-regression',
    'needs: [go-contract, android-companion-jvm]','connectedDebugAndroidTest'):
   self.assertIn(marker,source)

if __name__=='__main__':unittest.main(verbosity=2)
