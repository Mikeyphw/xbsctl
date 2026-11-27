#!/usr/bin/env python3
"""R07A negative cases for observed GitHub receipts; zero remote access."""
import importlib.util
from pathlib import Path
import unittest
import copy
import tempfile
import json

P=Path(__file__).with_name('r07a_gh_qualification.py')
spec=importlib.util.spec_from_file_location('r07a_auditor',P)
A=importlib.util.module_from_spec(spec);spec.loader.exec_module(A)
SHA='a'*40
RUN=123
ATTEMPT=2

def job(name):
    return {'name':name,'id':42+list(A.REQUIRED).index(name),'status':'completed','conclusion':'success',
            'steps':[{'name':label,'conclusion':'success'} for label in A.REQUIRED[name]]}

class AuditorTest(unittest.TestCase):
    def test_real_job_inventory_synthetic(self):
        self.assertEqual(set(A.qualify_jobs([job(x) for x in A.REQUIRED])),set(A.REQUIRED))
    def test_failed_or_missing_job_refuses(self):
        jobs=[job(x) for x in A.REQUIRED]
        for mutant in [jobs[:-1],jobs+[copy.deepcopy(jobs[0])],
                       [dict(jobs[0],conclusion='failure')]+jobs[1:],
                       [dict(jobs[0],steps=[])]+jobs[1:]]:
            with self.assertRaises(A.Refused):A.qualify_jobs(mutant)
    def test_correct_run_selection_requires_exact_head(self):
        valid={'id':12,'name':A.WORKFLOW,'head_sha':SHA,'head_branch':A.BRANCH,
               'event':'pull_request','status':'completed','conclusion':'success','run_attempt':1}
        self.assertEqual(A.choose([valid],SHA,A.BRANCH)['id'],12)
        for mutation in [{'head_sha':'b'*40},{'status':'in_progress'},{'conclusion':'failure'},
                         {'event':'workflow_dispatch'},{'run_attempt':0}]:
            candidate={**valid,**mutation}
            with self.assertRaises(A.Refused):A.choose([candidate],SHA,A.BRANCH)
    def test_real_count_log_parser_negative(self):
        valid={
            'go-contract':'XBS_R06B_HF03_ACTUAL_CASE_COUNTS_PASS dist/r06b-go-test-counts.json cases=292\nXBS_R06B_ACTUAL_NATIVE_PLAN_AND_PREFLIGHT_PASS nodes=6 affected_cases=3',
            'android-companion-jvm':'XBS_R05C_HF04_FULL_PYTHON_CI_ALL_TESTS_PASS files=20 cases=200\nXBS_R06B_HF03_ACTUAL_CASE_COUNTS_PASS android-evidence/r06b-jvm-test-counts.json cases=223',
            'android-companion-api36':'XBS_R05C_API36_INSTRUMENTATION_CASES_PASS 16',
        }
        self.assertEqual(A.counts_from_logs(valid),{'go':292,'jvm':223,'python':200,'api36':16})
        for replace in [{'go-contract':'CI passed'},
                        {'android-companion-api36':'XBS_R05C_API36_INSTRUMENTATION_CASES_PASS 0'},
                        {'android-companion-api36':'XBS_R05C_API36_INSTRUMENTATION_CASES_PASS 15'},
                        {'android-companion-jvm':'Ran 2 tests'}]:
            with self.assertRaises(A.Refused):A.counts_from_logs({**valid,**replace})
    def test_wrong_sha_run_and_attempt_refused(self):
        good={'xbs_source_sha':SHA,'github_run_id':str(RUN),'github_run_attempt':str(ATTEMPT),
              'test_cases':17,'failed_cases':0,'unexpected_skipped_cases':0}
        A.check_receipt(good,SHA,RUN,ATTEMPT,17)
        for patch in [{'xbs_source_sha':'b'*40},{'github_run_id':'124'},
                      {'github_run_attempt':'1'},{'test_cases':0},{'failed_cases':1},
                      {'unexpected_skipped_cases':1}]:
            with self.assertRaises(A.Refused):A.check_receipt({**good,**patch},SHA,RUN,ATTEMPT,17)
    def test_missing_expired_duplicate_artifact_refused(self):
        items=[{'name':f'xbs-android-module-{RUN}-{ATTEMPT}','size_in_bytes':7,'expired':False},
               {'name':f'xbs-android-jvm-counts-{RUN}-{ATTEMPT}','size_in_bytes':7,'expired':False},
               {'name':f'xbs-companion-r05c-api36-{RUN}-{ATTEMPT}','size_in_bytes':7,'expired':False}]
        self.assertEqual(len(A.artifact_list(items,RUN,ATTEMPT)),3)
        for invalid in [items[:-1],items+items[:1], [dict(items[0],expired=True)]+items[1:],
                        [dict(items[0],size_in_bytes=0)]+items[1:]]:
            with self.assertRaises(A.Refused):A.artifact_list(invalid,RUN,ATTEMPT)
    def test_gh_auditor_has_no_trigger_calls(self):
        source=P.read_text()
        for forbidden in ["'workflow','run'","'run','rerun'","'run','cancel'",'gh pr merge',"'release','create'"]:
            self.assertNotIn(forbidden,source)

if __name__=='__main__':unittest.main(verbosity=2)
