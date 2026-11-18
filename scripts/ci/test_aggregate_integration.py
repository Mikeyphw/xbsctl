#!/usr/bin/env python3
"""Negative tests for source-bound S7 P05 / PR35 aggregate readiness."""
import unittest
from aggregate_integration import verify_blob_map,Refused,MAIN_FILES,GRADLE_BLOB,AGG_BRANCH,R08R10_HEAD,MAIN_SHA,MERGE_TREE

class AggregateTests(unittest.TestCase):
    def fixture(self):return dict(MAIN_FILES, **{'android-app/build.gradle':GRADLE_BLOB})
    def test_all_main_blobs_present(self):self.assertTrue(verify_blob_map(self.fixture()))
    def test_distinct_named_branch(self):self.assertTrue(AGG_BRANCH.startswith('feature/xbs-aggregate-'))
    def test_pr35_exact_parent(self):self.assertEqual(R08R10_HEAD,'77825deb673c4973e82b9c428efcbc5991409a11')
    def test_main_exact_pin(self):self.assertEqual(MAIN_SHA,'c52561dfa203090befb680a058aa5e5e79870f1b')
    def test_merge_preview_exact_pin(self):self.assertEqual(MERGE_TREE,'ae7292929149833cbecd65667d9a4f7b0588697d')
    def test_no_extra_unreviewed_file(self):
        d=self.fixture();d['evil']='0'*40
        with self.assertRaises(Refused):verify_blob_map(d)
    def test_missing_source_file(self):
        d=self.fixture();d.pop(next(iter(MAIN_FILES)))
        with self.assertRaises(Refused):verify_blob_map(d)
    def test_tampered_source_file(self):
        for p in MAIN_FILES:
            with self.subTest(p=p):
                d=self.fixture();d[p]='0'*40
                with self.assertRaises(Refused):verify_blob_map(d)
    def test_gradle_guard_not_lost(self):
        d=self.fixture();d['android-app/build.gradle']='0'*40
        with self.assertRaises(Refused):verify_blob_map(d)

if __name__=='__main__':unittest.main(verbosity=2)
