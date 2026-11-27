#!/usr/bin/env python3
"""Negative tests for source-bound S7 P05 / PR35 aggregate readiness."""
import unittest
from aggregate_integration import verify_blob_map,Refused,MAIN_FILES,GRADLE_BLOB,AGG_BRANCH,R08R10_HEAD,MAIN_SHA,MERGE_TREE

class AggregateTests(unittest.TestCase):
    def test_r07a_fix_does_not_rewrite_aggregate_source(self):
        from aggregate_integration import AGG_HEAD,FIX_BRANCH
        self.assertEqual(AGG_HEAD,'b693612fc76bef3bd4d7fb8375861a990180fd0a')
        self.assertTrue(FIX_BRANCH.startswith('feature/xbs-aggregate-r07a-'))
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



from pathlib import Path
import subprocess
import tempfile
import unittest
from dataclasses import replace
from stack_ancestry import Segment, verify_reviewed_history, HistoryRefused

class HistoryTests(unittest.TestCase):
    def setUp(self):
        t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup);self.root=Path(t.name)
        self.git('init','-q');self.git('config','user.name','fixture');self.git('config','user.email','fixture@example.invalid')
        self.base=self.commit('base',{'base':'base'})
        self.names=['GX06','R08R10','AGG','FIX','HF01'];self.tips=[];self.segs=[]
        parent=self.base
        for i,name in enumerate(self.names):
            changes={name+'.py':name,'shared.py':name}
            tip=self.commit(name,changes);self.tips.append(tip)
            self.segs.append(Segment(name,parent,tip if i<4 else None,frozenset(changes)))
            parent=tip
        self.segs=tuple(self.segs)
    def git(self,*a):
        return subprocess.check_output(['git','-C',str(self.root),*a],stderr=subprocess.STDOUT,text=True).strip()
    def commit(self,name,changes):
        for p,v in changes.items():(self.root/p).write_text(v)
        self.git('add','-A');self.git('commit','-qm',name);return self.git('rev-parse','HEAD')
    def at(self,sha):self.git('checkout','--detach','-q',sha)
    def test_all_five_terminal_stages(self):
        for name,tip in zip(self.names,self.tips):
            with self.subTest(name=name):self.at(tip);self.assertEqual(verify_reviewed_history(self.root,self.segs),name)
    def test_historical_delta_not_current_head(self):
        self.at(self.tips[3]);self.assertNotEqual(set(self.git('diff','--name-only',self.tips[0],'HEAD').splitlines()),self.segs[1].paths)
        self.assertEqual(verify_reviewed_history(self.root,self.segs),'FIX')
    def test_extra_path_refused(self):
        self.at(self.tips[3]);self.commit('bad',{'HF01.py':'x','shared.py':'y','evil':'bad'})
        with self.assertRaisesRegex(HistoryRefused,'CHANGED_PATHS_DRIFT HF01'):verify_reviewed_history(self.root,self.segs)
    def test_missing_path_refused(self):
        self.at(self.tips[3]);self.commit('bad',{'HF01.py':'x'})
        with self.assertRaisesRegex(HistoryRefused,'CHANGED_PATHS_DRIFT HF01'):verify_reviewed_history(self.root,self.segs)
    def test_unknown_parent_refused(self):
        self.commit('new',{'random':'x'})
        with self.assertRaisesRegex(HistoryRefused,'UNREVIEWED_PARENT'):verify_reviewed_history(self.root,self.segs)
    def test_historical_inventory_drift_refused(self):
        seg=list(self.segs);seg[1]=replace(seg[1],paths=frozenset({'shared.py'}))
        with self.assertRaisesRegex(HistoryRefused,'CHANGED_PATHS_DRIFT R08R10'):verify_reviewed_history(self.root,tuple(seg))
    def test_historical_pin_drift_refused(self):
        seg=list(self.segs);seg[1]=replace(seg[1],pinned_tip=self.tips[2])
        with self.assertRaisesRegex(HistoryRefused,'DISCONTINUOUS_PIN_CHAIN'):verify_reviewed_history(self.root,tuple(seg))
    def test_merge_commit_refused(self):
        self.at(self.tips[3]);self.git('checkout','-qb','other');self.commit('other',{'other':'x'});self.at(self.tips[4]);self.git('merge','--no-ff','-m','merge','other')
        with self.assertRaisesRegex(HistoryRefused,'CURRENT_COMMIT_NOT_SINGLE_PARENT'):verify_reviewed_history(self.root,self.segs)


class StagePolicyTests(unittest.TestCase):
    def test_same_pr_base_and_branch_preserved(self):
        from gx06_stack_gate import stage_base_branch,stage_branch,AGG_BRANCH,FIX_BRANCH
        self.assertEqual(stage_base_branch('HF01'),AGG_BRANCH)
        self.assertEqual(stage_branch('HF01'),FIX_BRANCH)
    def test_hf01_expected_inventory_exact(self):
        from gx06_stack_gate import HF01_CHANGED_PATHS,FIX_HEAD
        self.assertEqual(FIX_HEAD,'5cfa4e6e846263d85338b5d394e201fd6ac4f6c1')
        self.assertEqual(HF01_CHANGED_PATHS, frozenset({
            'conftest.py',
            'docs/engineering/XBS_AGGREGATE_INTEGRATION_20261118.md',
            'scripts/ci/aggregate_integration.py',
            'scripts/ci/gx02_ci_verify.py',
            'scripts/ci/gx03_contract_audit.py',
            'scripts/ci/gx06_stack_gate.py',
            'scripts/ci/r08_r10_acceptance.py',
            'scripts/ci/stack_ancestry.py',
            'scripts/ci/test_aggregate_integration.py',
            'scripts/ci/test_r08_r10_acceptance.py',
            'scripts/ci/test_gx06_stack_gate.py',
        }))

if __name__=='__main__':unittest.main(verbosity=2)
