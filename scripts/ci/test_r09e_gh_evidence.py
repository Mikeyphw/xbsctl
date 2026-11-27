#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import unittest
p=Path(__file__).with_name('r09e_gh_evidence.py')
s=importlib.util.spec_from_file_location('e',p);M=importlib.util.module_from_spec(s);s.loader.exec_module(M)
class Tests(unittest.TestCase):
 def test_run_identity_exact(self):
  r={'head_sha':'a'*40,'head_branch':M.BRANCH,'name':M.WORKFLOW,'event':'pull_request',
   'id':123,'run_attempt':1,'status':'completed','conclusion':'success'}
  self.assertEqual(M.check_run(r,'a'*40),(123,1))
  for change in [{'head_sha':'b'*40},{'head_branch':'other'},{'event':'push'},
   {'conclusion':'failure'},{'status':'queued'},{'run_attempt':0}]:
   with self.subTest(change=change),self.assertRaises(M.Refused):M.check_run({**r,**change},'a'*40)
 def test_required_go_api36_jvm_and_prior_step(self):
  jobs=[{'name':name,'status':'completed','conclusion':'success','steps':
   ([{'name':M.STEP,'conclusion':'success'},{'name':'R09D crash recovery and complete risk coverage inventory','conclusion':'success'}] if name=='go-contract' else [])}
   for name in ('go-contract','android-companion-jvm','android-companion-api36')]
  self.assertEqual(len(M.check_jobs(jobs)),3)
  for bad in [jobs[:2],jobs+[jobs[-1]],[{**jobs[0],'conclusion':'skipped'},*jobs[1:]],
   [{**jobs[0],'steps':jobs[0]['steps'][:1]},*jobs[1:]]]:
   with self.assertRaises(M.Refused):M.check_jobs(bad)
 def test_logs_are_receipt_bound(self):
  receipt={'root_tests':7,'repeat_pass_events':21,'race_pass_events':7,
   'coverage':{'current_full_pct':72.3,'baseline_full_pct':71.29,'measured_change_pp':1.01}}
  good='XBS_R09E_HOSTED_BOUNDARY_PASS tests=7 repeat=21 race=7 coverage=72.3 baseline=71.29 delta_pp=1.01'
  M.check_log(good,receipt)
  for bad in [good+ '\n'+good,good.replace('tests=7','tests=8'),good.replace('delta_pp=1.01','delta_pp=-1'),'',good.replace('race=7','race=0')]:
   with self.assertRaises(M.Refused):M.check_log(bad,receipt)
if __name__=='__main__':unittest.main(verbosity=2)
