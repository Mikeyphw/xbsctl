#!/usr/bin/env python3
"""R09B observed-GitHub auditor negative tests without network/gh process."""
from __future__ import annotations
import importlib.util
from pathlib import Path
import unittest
P=Path(__file__).with_name('r09b_gh_evidence.py')
S=importlib.util.spec_from_file_location('r09b_gha',P)
A=importlib.util.module_from_spec(S);S.loader.exec_module(A)
SHA='a'*40
class R09BHostedAuditTest(unittest.TestCase):
    def goodrun(self):return {'head_sha':SHA,'head_branch':A.BRANCH,'event':'pull_request','name':A.WORKFLOW,
        'status':'completed','conclusion':'success','id':123,'run_attempt':2}
    def goodjobs(self):
        out=[{'name':name,'id':i+7,'status':'completed','conclusion':'success','steps':[]}
            for i,name in enumerate(['go-contract','android-companion-jvm','android-companion-api36'])]
        out[0]['steps']=[{'name':A.STEP,'conclusion':'success'}]
        return out
    def test_exact_run_identity(self):
        self.assertEqual(A.audit_run(self.goodrun(),SHA),(123,2))
        for bad in [{'head_sha':'b'*40},{'head_branch':'wrong'},{'event':'push'},{'run_attempt':0},
                    {'status':'queued'},{'conclusion':'failure'},{'name':'other'}]:
            with self.subTest(bad=bad),self.assertRaises(A.Refused):A.audit_run({**self.goodrun(),**bad},SHA)
    def test_three_required_jobs_and_step(self):
        self.assertEqual(len(A.audit_jobs(self.goodjobs())),3)
        for mutated in [self.goodjobs()[:2],self.goodjobs()+self.goodjobs()[:1],
                        [dict(self.goodjobs()[0],conclusion='failure')]+self.goodjobs()[1:],
                        [dict(self.goodjobs()[0],steps=[])]+self.goodjobs()[1:]]:
            with self.assertRaises(A.Refused):A.audit_jobs(mutated)
    def test_real_log_count_matches_receipt(self):
        data={'required_root_tests':11,'repeat_passed_root_events':33,'race_passed_root_events':11}
        A.validate_log('XBS_R09B_BACKEND_MATRIX_PASS tests=11 repeat=33 race=11 domains=4',data)
        for bad in ['passed','XBS_R09B_BACKEND_MATRIX_PASS tests=10 repeat=33 race=11 domains=4',
                    'XBS_R09B_BACKEND_MATRIX_PASS tests=11 repeat=33 race=11 domains=0',
                    'XBS_R09B_BACKEND_MATRIX_PASS tests=11 repeat=33 race=11 domains=4\nXBS_R09B_BACKEND_MATRIX_PASS tests=11 repeat=33 race=11 domains=4']:
            with self.assertRaises(A.Refused):A.validate_log(bad,data)
    def test_no_mutating_github_actions(self):
        text=P.read_text()
        for forbidden in ["'workflow','run'","'run','rerun'","'pr','merge'","'release','create'"]:
            self.assertNotIn(forbidden,text)
if __name__=='__main__':unittest.main(verbosity=2)
