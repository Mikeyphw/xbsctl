#!/usr/bin/env python3
"""R09D independent GH auditor unit negatives; zero remote calls."""
from __future__ import annotations
import importlib.util
from pathlib import Path
import unittest
import copy
import json

ROOT=Path(__file__).resolve().parents[2]
P=Path(__file__).with_name('r09d_gh_evidence.py')
spec=importlib.util.spec_from_file_location('auditor',P)
A=importlib.util.module_from_spec(spec);spec.loader.exec_module(A)
SHA='a'*40

def make_jobs():
 return [{'name':name,'status':'completed','conclusion':'success','id':100+i,
  'steps':([{'name':A.STEP,'conclusion':'success'}] if name=='go-contract' else [])}
  for i,name in enumerate(['go-contract','android-companion-jvm','android-companion-api36'])]

class GithubTests(unittest.TestCase):
 def test_run_identity_and_attempt(self):
  valid={'id':77,'run_attempt':2,'head_branch':A.BRANCH,'head_sha':SHA,
   'event':'pull_request','name':A.WORKFLOW,'status':'completed','conclusion':'success'}
  self.assertEqual(A.check_run(valid,SHA),(77,2))
  for patch in ({'head_sha':'b'*40},{'head_branch':'wrong'},{'event':'push'},
       {'conclusion':'failure'},{'status':'in_progress'},{'run_attempt':0}):
   with self.subTest(patch=patch),self.assertRaises(A.Refused):A.check_run({**valid,**patch},SHA)
 def test_missing_skipped_and_duplicate_required_jobs(self):
  jobs=make_jobs();self.assertEqual(len(A.check_jobs(jobs)),3)
  for candidate in (jobs[:-1],jobs+jobs[:1],[dict(jobs[0],conclusion='skipped')]+jobs[1:],
     [dict(jobs[0],steps=[])]+jobs[1:]):
   with self.assertRaises(A.Refused):A.check_jobs(candidate)
 def test_log_count_receipt_parity(self):
  receipt={'required_root_tests':6,'repeat_root_events':18,'race_root_events':6,
   'all_internal_coverage_pct':23.4,'coverage_repeated_observations':400,
   'unqualified_risk_targets':['cli','control'],
   'full_go_suite':{'no_test_package_skip_count':1}}
  log='XBS_R09D_REAL_BACKEND_PASS tests=6 repeat=18 race=6 risk_packages=7 total_coverage_pct=23.4 below_target=2 hosted_skips=2 no_test_packages=2 package_only_skips=1 merged_observations=400\n'
  A.check_log(log,receipt)
  for mutant in (log.replace('repeat=18','repeat=6'),log.replace('risk_packages=7','risk_packages=8'),
       log.replace('below_target=2','below_target=0'),
       log.replace('hosted_skips=2','hosted_skips=0'),
       log.replace('no_test_packages=2','no_test_packages=1'),
       log.replace('package_only_skips=1','package_only_skips=2'),
       log.replace('merged_observations=400','merged_observations=399'),log+log):
   with self.assertRaises(A.Refused):A.check_log(mutant,receipt)
 def test_auditor_is_read_only(self):
  text=P.read_text()
  for forbidden in ("'workflow','run'","'run','rerun'","'pr','merge'","'release','create'","'push'"):
   self.assertNotIn(forbidden,text)

if __name__=='__main__':unittest.main(verbosity=2)
