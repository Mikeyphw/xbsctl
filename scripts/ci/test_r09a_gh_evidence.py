#!/usr/bin/env python3
"""R09A receipt negative tests; no GitHub calls."""
import importlib.util
from pathlib import Path
import json
import unittest

S=importlib.util.spec_from_file_location('r09audit',Path(__file__).with_name('r09a_gh_evidence.py'))
assert S and S.loader
A=importlib.util.module_from_spec(S);S.loader.exec_module(A)
import hashlib
SHA='a'*40
RUN=123
ATTEMPT=2
PROFILE=b'mode: atomic\n'
GOOD={'schema':A.SCHEMA,'xbs_source_sha':SHA,'github_run_id':str(RUN),'github_run_attempt':str(ATTEMPT),
      'test_cases':8,'repeated_test_cases':9,'failed_cases':0,'unexpected_skipped_cases':0,
      'fuzz_targets_executed':5,'fuzz_executions':{'a':10,'b':15,'c':100,'d':1,'e':10},
      'coverage_total_statements_pct':7.5,'coverage_profile_sha256':hashlib.sha256(PROFILE).hexdigest(),
      'physical_root_qualified':False,'device_mutated':False,'release_qualified':False}
class EvidenceTest(unittest.TestCase):
    def test_positive(self):
        self.assertEqual(A.check_receipt(GOOD,SHA,RUN,ATTEMPT,PROFILE)['fuzz_executions'],136)
    def test_mutations_refuse(self):
        edits=[{'schema':'fake'},{'xbs_source_sha':'b'*40},{'github_run_id':'5'},
               {'github_run_attempt':'1'},{'test_cases':0},{'repeated_test_cases':0},
               {'fuzz_targets_executed':0},{'fuzz_executions':{}},{'coverage_total_statements_pct':0},
               {'coverage_profile_sha256':'0'*64},{'device_mutated':True},{'physical_root_qualified':True},
               {'failed_cases':1},{'unexpected_skipped_cases':1}]
        for edit in edits:
            with self.subTest(edit=edit),self.assertRaises(A.Refused):A.check_receipt({**GOOD,**edit},SHA,RUN,ATTEMPT,PROFILE)
        with self.assertRaises(A.Refused):A.check_receipt(GOOD,SHA,RUN,ATTEMPT,PROFILE+b'changed')
    def test_no_remote_mutation_actions(self):
        source=Path(A.__file__).read_text()
        for forbidden in ["'workflow','run'","'run','rerun'","'run','cancel'",'gh pr merge']:
            self.assertNotIn(forbidden,source)
if __name__=='__main__':unittest.main(verbosity=2)
