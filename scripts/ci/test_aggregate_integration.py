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

class MainMergeTests(unittest.TestCase):
    git=HistoryTests.git
    commit=HistoryTests.commit
    at=HistoryTests.at
    def setUp(self):
        HistoryTests.setUp(self)
        from unittest.mock import patch
        import gx06_stack_gate as g
        self.old_tip=self.tips[-1]
        self.candidate=self.commit('INT01',{'int01':'integration'})
        self.at(self.base);self.git('checkout','-qb','main-fixture')
        # A separate main commit with an already-integrated change.
        self.main=self.commit('main',{'GX06.py':'GX06'})
        self.main_tree=self.git('rev-parse','HEAD^{tree}')
        self.at(self.candidate)
        pins={ 'GX05_HEAD':self.base,'GX06_HEAD':self.tips[0],'R08R10_HEAD':self.tips[1],
            'AGG_HEAD':self.tips[2],'FIX_HEAD':self.tips[3],'HF01_HEAD':self.tips[4],
            'AGG_MAIN_HEAD':self.main,'MAIN_TREE':self.main_tree,
            'GX06_CHANGED_PATHS':self.segs[0].paths,'R08R10_CHANGED_PATHS':self.segs[1].paths,
            'AGG_CHANGED_PATHS':self.segs[2].paths,'FIX_CHANGED_PATHS':self.segs[3].paths,
            'HF01_CHANGED_PATHS':self.segs[4].paths,'INT01_CHANGED_PATHS':frozenset({'int01'}) }
        ctx=patch.multiple(g,**pins);ctx.start();self.addCleanup(ctx.stop)
    def merge(self,first,second,tree=None):
        tree=tree or self.git('rev-parse',self.candidate+'^{tree}')
        sha=self.git('commit-tree',tree,'-p',first,'-p',second,'-m','test-only merge')
        self.at(sha);return sha
    def test_reviewed_main_merge_and_push_context(self):
        from gx06_stack_gate import reviewed_stage,validate_hosted_context,INT01_BRANCH
        self.assertEqual(reviewed_stage(self.root),'INT01')
        env={'GITHUB_ACTIONS':'true','GITHUB_EVENT_NAME':'pull_request','GITHUB_HEAD_REF':INT01_BRANCH,
             'GITHUB_BASE_REF':'main','XBS_EXPECTED_HEAD_SHA':self.candidate,'GITHUB_RUN_ID':'1','GITHUB_RUN_ATTEMPT':'1'}
        self.assertEqual(validate_hosted_context(self.root,env),('1','1'))
        head=self.merge(self.main,self.candidate)
        self.assertEqual(reviewed_stage(self.root),'INT01_MAIN')
        env={'GITHUB_ACTIONS':'true','GITHUB_EVENT_NAME':'push','GITHUB_REF':'refs/heads/main',
             'GITHUB_SHA':head,'XBS_EXPECTED_HEAD_SHA':head,'GITHUB_RUN_ID':'1','GITHUB_RUN_ATTEMPT':'1'}
        self.assertEqual(validate_hosted_context(self.root,env),('1','1'))
        env['GITHUB_REF']='refs/heads/other'
        from gx06_stack_gate import Refused
        with self.assertRaises(Refused):validate_hosted_context(self.root,env)
    def test_main_wrong_parent_order(self):
        from gx06_stack_gate import reviewed_stage,Refused
        self.merge(self.candidate,self.main)
        with self.assertRaisesRegex(Refused,'INT01_MAIN_PARENT_DRIFT'):reviewed_stage(self.root)
    def test_main_wrong_tree(self):
        from gx06_stack_gate import reviewed_stage,Refused
        self.merge(self.main,self.candidate,self.main_tree)
        with self.assertRaisesRegex(Refused,'INT01_MAIN_MERGE_TREE_DRIFT'):reviewed_stage(self.root)
    def test_main_unreviewed_second_parent(self):
        from gx06_stack_gate import reviewed_stage,Refused
        self.merge(self.main,self.old_tip)
        with self.assertRaisesRegex(Refused,'INT01_MAIN_CANDIDATE_NOT_REVIEWED'):reviewed_stage(self.root)
    def test_wrong_pr_base(self):
        from gx06_stack_gate import validate_hosted_context,INT01_BRANCH,Refused
        env={'GITHUB_ACTIONS':'true','GITHUB_EVENT_NAME':'pull_request','GITHUB_HEAD_REF':INT01_BRANCH,
             'GITHUB_BASE_REF':'other','XBS_EXPECTED_HEAD_SHA':self.candidate,'GITHUB_RUN_ID':'1','GITHUB_RUN_ATTEMPT':'1'}
        with self.assertRaisesRegex(Refused,'INT01_PR_BASE_DRIFT'):validate_hosted_context(self.root,env)
    def test_squash_rejected(self):
        from gx06_stack_gate import reviewed_stage,Refused
        self.at(self.main);self.commit('squash',{'int01':'integration'})
        with self.assertRaisesRegex(Refused,'UNREVIEWED_PARENT'):reviewed_stage(self.root)

class Ui01MainTests(unittest.TestCase):
    git=HistoryTests.git
    commit=HistoryTests.commit
    at=HistoryTests.at
    merge=MainMergeTests.merge
    def setUp(self):
        MainMergeTests.setUp(self)
        from unittest.mock import patch
        import gx06_stack_gate as g
        integrated=self.merge(self.main,self.candidate)
        tree=self.git('rev-parse','HEAD^{tree}')
        ctx=patch.multiple(g,INT01_PUBLISHED_HEAD=self.candidate,INTEGRATED_MAIN_HEAD=integrated,
                           INTEGRATED_MAIN_TREE=tree,UI01_CHANGED_PATHS=frozenset({'ui01'}))
        ctx.start();self.addCleanup(ctx.stop)
        self.integrated=integrated
        self.ui=self.commit('UI01',{'ui01':'presentation'})
    def test_ui01_candidate_and_merge(self):
        from gx06_stack_gate import reviewed_stage,validate_hosted_context,UI01_BRANCH
        self.assertEqual(reviewed_stage(self.root),'UI01')
        env={'GITHUB_ACTIONS':'true','GITHUB_EVENT_NAME':'pull_request','GITHUB_HEAD_REF':UI01_BRANCH,
             'GITHUB_BASE_REF':'main','XBS_EXPECTED_HEAD_SHA':self.ui,'GITHUB_RUN_ID':'2','GITHUB_RUN_ATTEMPT':'1'}
        self.assertEqual(validate_hosted_context(self.root,env),('2','1'))
        head=self.merge(self.integrated,self.ui,self.git('rev-parse',self.ui+'^{tree}'))
        self.assertEqual(reviewed_stage(self.root),'UI01_MAIN')
        env={'GITHUB_ACTIONS':'true','GITHUB_EVENT_NAME':'push','GITHUB_REF':'refs/heads/main',
             'GITHUB_SHA':head,'XBS_EXPECTED_HEAD_SHA':head,'GITHUB_RUN_ID':'2','GITHUB_RUN_ATTEMPT':'1'}
        self.assertEqual(validate_hosted_context(self.root,env),('2','1'))
    def test_ui01_wrong_paths(self):
        from gx06_stack_gate import reviewed_stage,Refused
        self.at(self.integrated);self.commit('drift',{'evil':'bad'})
        with self.assertRaisesRegex(Refused,'CHANGED_PATHS_DRIFT UI01'):reviewed_stage(self.root)
    def test_ui01_wrong_merge_tree(self):
        from gx06_stack_gate import reviewed_stage,Refused
        self.merge(self.integrated,self.ui,self.git('rev-parse',self.integrated+'^{tree}'))
        with self.assertRaisesRegex(Refused,'UI01_MERGE_TREE_DRIFT'):reviewed_stage(self.root)


class Ui01HotfixTests(unittest.TestCase):
    git=HistoryTests.git
    commit=HistoryTests.commit
    at=HistoryTests.at
    merge=MainMergeTests.merge
    def setUp(self):
        Ui01MainTests.setUp(self)
        from unittest.mock import patch
        import gx06_stack_gate as g
        ctx=patch.multiple(g,UI01_PUBLISHED_HEAD=self.ui,UI01_PUBLISHED_TREE=self.git('rev-parse','HEAD^{tree}'),
                           UI01_HF01_CHANGED_PATHS=frozenset({'ui01'}))
        ctx.start();self.addCleanup(ctx.stop)
        self.hf=self.commit('UI01 HF01',{'ui01':'fixed'})
    def test_same_pr_and_main_merge(self):
        from gx06_stack_gate import reviewed_stage,validate_hosted_context,UI01_BRANCH
        self.assertEqual(reviewed_stage(self.root),'UI01_HF01')
        env={'GITHUB_ACTIONS':'true','GITHUB_EVENT_NAME':'pull_request','GITHUB_HEAD_REF':UI01_BRANCH,
             'GITHUB_BASE_REF':'main','XBS_EXPECTED_HEAD_SHA':self.hf,'GITHUB_RUN_ID':'3','GITHUB_RUN_ATTEMPT':'1'}
        self.assertEqual(validate_hosted_context(self.root,env),('3','1'))
        env['GITHUB_HEAD_REF']='other'
        from gx06_stack_gate import Refused
        with self.assertRaises(Refused):validate_hosted_context(self.root,env)
        head=self.merge(self.integrated,self.hf,self.git('rev-parse',self.hf+'^{tree}'))
        self.assertEqual(reviewed_stage(self.root),'UI01_HF01_MAIN')
        env={'GITHUB_ACTIONS':'true','GITHUB_EVENT_NAME':'push','GITHUB_REF':'refs/heads/main',
             'GITHUB_SHA':head,'XBS_EXPECTED_HEAD_SHA':head,'GITHUB_RUN_ID':'3','GITHUB_RUN_ATTEMPT':'1'}
        self.assertEqual(validate_hosted_context(self.root,env),('3','1'))
    def test_extra_path_refused(self):
        from gx06_stack_gate import reviewed_stage,Refused
        self.at(self.ui);self.commit('drift',{'ui01':'fixed','evil':'bad'})
        with self.assertRaisesRegex(Refused,'CHANGED_PATHS_DRIFT UI01_HF01'):reviewed_stage(self.root)
    def test_preimage_tree_refused(self):
        from unittest.mock import patch
        from gx06_stack_gate import reviewed_stage,Refused
        with patch('gx06_stack_gate.UI01_PUBLISHED_TREE','0'*40),self.assertRaisesRegex(Refused,'UI01_HF01_PREIMAGE_TREE_DRIFT'):
            reviewed_stage(self.root)
    def test_wrong_merge_tree_refused(self):
        from gx06_stack_gate import reviewed_stage,Refused
        self.merge(self.integrated,self.hf,self.git('rev-parse',self.ui+'^{tree}'))
        with self.assertRaisesRegex(Refused,'UI01_MERGE_TREE_DRIFT'):reviewed_stage(self.root)
    def test_extra_commit_refused(self):
        from gx06_stack_gate import reviewed_stage,Refused
        self.commit('unreviewed child',{'ui01':'another'})
        with self.assertRaises(Refused):reviewed_stage(self.root)

class Ui02MergeTests(unittest.TestCase):
    git=HistoryTests.git
    commit=HistoryTests.commit
    at=HistoryTests.at
    merge=MainMergeTests.merge
    def setUp(self):
        Ui01HotfixTests.setUp(self)
        from unittest.mock import patch
        import gx06_stack_gate as g
        self.prior=self.merge(self.integrated,self.hf,self.git('rev-parse',self.hf+'^{tree}'))
        ctx=patch.multiple(g,UI01_HF01_PUBLISHED_HEAD=self.hf,UI01_MERGED_HEAD=self.prior,
                           UI01_MERGED_TREE=self.git('rev-parse','HEAD^{tree}'),UI02_CHANGED_PATHS=frozenset({'ui02'}))
        ctx.start();self.addCleanup(ctx.stop)
        self.next=self.commit('UI02',{'ui02':'editors'})
    def test_candidate_and_merge(self):
        from gx06_stack_gate import reviewed_stage,validate_hosted_context,UI02_BRANCH
        self.assertEqual(reviewed_stage(self.root),'UI02')
        env={'GITHUB_ACTIONS':'true','GITHUB_EVENT_NAME':'pull_request','GITHUB_HEAD_REF':UI02_BRANCH,
             'GITHUB_BASE_REF':'main','XBS_EXPECTED_HEAD_SHA':self.next,'GITHUB_RUN_ID':'4','GITHUB_RUN_ATTEMPT':'1'}
        self.assertEqual(validate_hosted_context(self.root,env),('4','1'))
        head=self.merge(self.prior,self.next,self.git('rev-parse',self.next+'^{tree}'))
        self.assertEqual(reviewed_stage(self.root),'UI02_MAIN')
        env={'GITHUB_ACTIONS':'true','GITHUB_EVENT_NAME':'push','GITHUB_REF':'refs/heads/main',
             'GITHUB_SHA':head,'XBS_EXPECTED_HEAD_SHA':head,'GITHUB_RUN_ID':'4','GITHUB_RUN_ATTEMPT':'1'}
        self.assertEqual(validate_hosted_context(self.root,env),('4','1'))
    def test_extra_path_refused(self):
        from gx06_stack_gate import reviewed_stage,Refused
        self.at(self.prior);self.commit('drift',{'ui02':'editors','evil':'bad'})
        with self.assertRaisesRegex(Refused,'CHANGED_PATHS_DRIFT UI02'):reviewed_stage(self.root)
    def test_prior_tree_refused(self):
        from unittest.mock import patch
        from gx06_stack_gate import reviewed_stage,Refused
        with patch('gx06_stack_gate.UI01_MERGED_TREE','0'*40),self.assertRaisesRegex(Refused,'UI02_PREVIOUS_TREE_DRIFT'):
            reviewed_stage(self.root)
    def test_unreviewed_merge_tree_refused(self):
        from gx06_stack_gate import reviewed_stage,Refused
        self.merge(self.prior,self.next,self.git('rev-parse',self.prior+'^{tree}'))
        with self.assertRaisesRegex(Refused,'UI02_MERGE_TREE_DRIFT'):reviewed_stage(self.root)
    def test_extra_child_refused(self):
        from gx06_stack_gate import reviewed_stage,Refused
        self.commit('unreviewed child',{'ui02':'another'})
        with self.assertRaises(Refused):reviewed_stage(self.root)

class Ui03MergeTests(unittest.TestCase):
    git=HistoryTests.git
    commit=HistoryTests.commit
    at=HistoryTests.at
    merge=MainMergeTests.merge
    def setUp(self):
        Ui02MergeTests.setUp(self)
        from unittest.mock import patch
        import gx06_stack_gate as g
        previous=self.next
        self.prior=self.merge(self.prior,self.next,self.git('rev-parse',self.next+'^{tree}'))
        ctx=patch.multiple(g,UI02_PUBLISHED_HEAD=previous,UI02_MERGED_HEAD=self.prior,
                           UI02_MERGED_TREE=self.git('rev-parse','HEAD^{tree}'),UI03_CHANGED_PATHS=frozenset({'ui03'}))
        ctx.start();self.addCleanup(ctx.stop)
        self.next=self.commit('UI03',{'ui03':'editors'})
    def test_candidate_and_merge(self):
        from gx06_stack_gate import reviewed_stage,validate_hosted_context,UI03_BRANCH
        self.assertEqual(reviewed_stage(self.root),'UI03')
        env={'GITHUB_ACTIONS':'true','GITHUB_EVENT_NAME':'pull_request','GITHUB_HEAD_REF':UI03_BRANCH,
             'GITHUB_BASE_REF':'main','XBS_EXPECTED_HEAD_SHA':self.next,'GITHUB_RUN_ID':'4','GITHUB_RUN_ATTEMPT':'1'}
        self.assertEqual(validate_hosted_context(self.root,env),('4','1'))
        head=self.merge(self.prior,self.next,self.git('rev-parse',self.next+'^{tree}'))
        self.assertEqual(reviewed_stage(self.root),'UI03_MAIN')
        env={'GITHUB_ACTIONS':'true','GITHUB_EVENT_NAME':'push','GITHUB_REF':'refs/heads/main',
             'GITHUB_SHA':head,'XBS_EXPECTED_HEAD_SHA':head,'GITHUB_RUN_ID':'4','GITHUB_RUN_ATTEMPT':'1'}
        self.assertEqual(validate_hosted_context(self.root,env),('4','1'))
    def test_extra_path_refused(self):
        from gx06_stack_gate import reviewed_stage,Refused
        self.at(self.prior);self.commit('drift',{'ui03':'editors','evil':'bad'})
        with self.assertRaisesRegex(Refused,'CHANGED_PATHS_DRIFT UI03'):reviewed_stage(self.root)
    def test_prior_tree_refused(self):
        from unittest.mock import patch
        from gx06_stack_gate import reviewed_stage,Refused
        with patch('gx06_stack_gate.UI02_MERGED_TREE','0'*40),self.assertRaisesRegex(Refused,'UI03_PREVIOUS_TREE_DRIFT'):
            reviewed_stage(self.root)
    def test_unreviewed_merge_tree_refused(self):
        from gx06_stack_gate import reviewed_stage,Refused
        self.merge(self.prior,self.next,self.git('rev-parse',self.prior+'^{tree}'))
        with self.assertRaisesRegex(Refused,'UI03_MERGE_TREE_DRIFT'):reviewed_stage(self.root)
    def test_extra_child_refused(self):
        from gx06_stack_gate import reviewed_stage,Refused
        self.commit('unreviewed child',{'ui03':'another'})
        with self.assertRaises(Refused):reviewed_stage(self.root)

if __name__=='__main__':unittest.main(verbosity=2)
