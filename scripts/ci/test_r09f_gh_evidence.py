#!/usr/bin/env python3
"""Offline mutation tests for the R09F GitHub verifier."""
from __future__ import annotations
import copy
import importlib.util
from pathlib import Path
import unittest

P=Path(__file__).with_name('r09f_gh_evidence.py')
spec=importlib.util.spec_from_file_location('r09f_auditor',P)
A=importlib.util.module_from_spec(spec);spec.loader.exec_module(A)
SHA='a'*40
class AuditorTests(unittest.TestCase):
 def run_fixture(self):return {'head_sha':SHA,'head_branch':A.BRANCH,'event':'pull_request','name':A.WORKFLOW,
                               'status':'completed','conclusion':'success','id':19,'run_attempt':1}
 def jobs_fixture(self):
  return [{'name':name,'status':'completed','conclusion':'success','id':i,
           'steps':([{'name':n,'conclusion':'success'} for n in (
              'R09A bounded backend fuzz, repeat and coverage',
              'R09B deterministic fault injection and zip negatives',
              'R09C fake Android backend and CLI golden contracts',
              'R09D crash recovery and complete risk coverage inventory',
              'R09E seven-package boundaries and measured coverage non-regression',A.STEP)] if name=='go-contract' else [])}
          for i,name in enumerate(['go-contract','android-companion-jvm','android-companion-api36'],1)]
 def test_exact_run_and_identity_refusals(self):
  r=self.run_fixture();self.assertEqual(A.check_run(r,SHA),(19,1))
  for patch in ({'head_sha':'b'*40},{'head_branch':'wrong'},{'status':'in_progress'},
                {'conclusion':'failure'},{'event':'push'},{'run_attempt':0},{'name':'wrong'}):
   with self.subTest(patch=patch),self.assertRaises(A.Refused):A.check_run({**r,**patch},SHA)
 def test_required_jobs_all_six_steps(self):
  x=self.jobs_fixture();self.assertIn('go-contract',A.check_jobs(x))
  for i in range(3):
   y=copy.deepcopy(x);y[i]['conclusion']='failure'
   with self.assertRaises(A.Refused):A.check_jobs(y)
  for i in range(6):
   y=copy.deepcopy(x);y[0]['steps'][i]['conclusion']='skipped'
   with self.assertRaises(A.Refused):A.check_jobs(y)
 def test_duplicate_job_and_missing_step(self):
  x=self.jobs_fixture()
  with self.assertRaises(A.Refused):A.check_jobs(x+[copy.deepcopy(x[0])])
  with self.assertRaises(A.Refused):A.check_jobs(x[:2])
  with self.assertRaises(A.Refused):A.check_jobs([dict(x[0],steps=x[0]['steps'][:-1]),*x[1:]])
 def test_log_strict_source_of_counts(self):
  evidence={'coverage':{'full_internal_pct':72.07}}
  line='XBS_R09F_ADVERSARIAL_PASS tests=6 subtests=88 repeat=18 repeat_subtests=264 race=6 race_subtests=88 coverage=72.07 stages=5'
  A.check_log(line,evidence)
  for bad in [line+'\n'+line,line.replace('tests=6','tests=5'),line.replace('coverage=72.07','coverage=72.06'),
              line.replace('stages=5','stages=4'),line.replace('race_subtests=88','race_subtests=87'),'']:
   with self.subTest(bad=bad[:30]),self.assertRaises(A.Refused):A.check_log(bad,evidence)
if __name__=='__main__':unittest.main(verbosity=2)
