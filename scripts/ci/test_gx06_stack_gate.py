#!/usr/bin/env python3
"""GX06 source/safety adversarial regressions (no GitHub or device)."""
import copy
import json
from pathlib import Path
import unittest
from gx06_stack_gate import (REPO,GX06_CHANGED_PATHS,R08R10_CHANGED_PATHS,R08R10_BRANCH,GX05_HEAD,
                             Refused,validated_lock,validate_original_inventory,
                             validate_release_limits)

HERE=Path(__file__).resolve().parents[2]
LOCK=HERE/'docs/engineering/XBS_GX06_STACK_LOCK.json'
COVERAGE=HERE/'docs/engineering/XBS_GX06_COVERAGE.json'

def original_fixture():
    n=[12,18,13,13,10,14,12,15,14,11,13,10,12,11]
    ledger={'phases':[{'id':f'R{i:02d}', 'tasks':[{'id':f'R{i:02d}-{j:02d}'} for j in range(1,k+1)]}
                      for i,k in enumerate(n)],
            'findings':[{'id':f'AF-{i:03d}'} for i in range(1,27)]}
    matrix={'cases':[{'id':f'EM-{i:03d}'} for i in range(1,132)]}
    tracker={'original_task_count':178,'original_test_case_count':131,
             'side_milestones':[{'id':f'GX{i:02d}'} for i in range(1,7)]}
    return ledger,matrix,tracker

class GX06StackTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.lock=json.loads(LOCK.read_text())
        cls.coverage=json.loads(COVERAGE.read_text())

    def refuse(self,mutate,code=''):
        a=copy.deepcopy(self.lock);mutate(a)
        with self.assertRaises(Refused) as cm:validated_lock(a)
        if code:self.assertIn(code,str(cm.exception))

    def test_lock_positive(self):
        self.assertEqual(len(validated_lock(self.lock)),5)
    def test_file_inventory_exact(self):
        self.assertEqual(set(self.lock['expected_changed_paths']),GX06_CHANGED_PATHS)
    def test_rebuilt_r08r10_inventory_exact(self):
        expected = {
            '.github/workflows/devtool-xbs-ci.yml',
            'android-app/app/src/androidTest/java/io/github/mikeceead/xbscompanion/Gx04TilesInstrumentedTest.java',
            'android-app/build.gradle',
            'config/delivery-required-ci.json',
            'docs/engineering/XBS_R08_R10_ACCEPTANCE_HANDOFF.md',
            'docs/engineering/XBS_R08_R10_EVIDENCE_MAP.json',
            'scripts/ci/gx02_ci_verify.py',
            'scripts/ci/gx03_contract_audit.py',
            'scripts/ci/gx03_emulator_host.py',
            'scripts/ci/gx06_stack_gate.py',
            'scripts/ci/r08_r10_acceptance.py',
            'scripts/ci/test_gx04_tiles.py',
            'scripts/ci/test_gx06_stack_gate.py',
            'scripts/ci/test_r08_r10_acceptance.py',
        }
        self.assertEqual(R08R10_CHANGED_PATHS, expected)
        self.assertEqual(R08R10_BRANCH, 'feature/xbs-r08-r10-acceptance-rebuild-20261118')
    def test_source_parent_exact(self):
        self.assertEqual(self.lock['base_head'],GX05_HEAD)
    def test_main_is_not_merge_claim(self):
        self.assertFalse(self.lock['auto_merge'])
    def test_physical_release_not_claimed(self):
        self.assertFalse(self.lock['physical_root_qualified']);self.assertFalse(self.lock['release_qualified'])
    def test_wrong_repo(self):self.refuse(lambda a:a.__setitem__('repository','foreign/repo'))
    def test_wrong_schema(self):self.refuse(lambda a:a.__setitem__('schema','v2'))
    def test_wrong_head(self):self.refuse(lambda a:a.__setitem__('base_head','0'*40))
    def test_wrong_tree(self):self.refuse(lambda a:a.__setitem__('base_tree','0'*40))
    def test_wrong_branch(self):self.refuse(lambda a:a.__setitem__('branch','main'))
    def test_removed_path(self):self.refuse(lambda a:a['expected_changed_paths'].pop())
    def test_extra_path(self):self.refuse(lambda a:a['expected_changed_paths'].append('other'))
    def test_duplicate_path(self):self.refuse(lambda a:a['expected_changed_paths'].append(a['expected_changed_paths'][0]))
    def test_missing_gx01(self):self.refuse(lambda a:a['stack'].pop(0))
    def test_extra_gx06_in_prior_stack(self):self.refuse(lambda a:a['stack'].append(copy.deepcopy(a['stack'][-1])))
    def test_wrong_pr_number(self):self.refuse(lambda a:a['stack'][2].__setitem__('pr',29))
    def test_duplicate_pr(self):self.refuse(lambda a:a['stack'][1].__setitem__('pr',27))
    def test_missing_base_branch(self):self.refuse(lambda a:a['stack'][3].__setitem__('base_branch','main'))
    def test_wrong_base_sha(self):self.refuse(lambda a:a['stack'][3].__setitem__('base_sha','0'*40))
    def test_wrong_head_format(self):self.refuse(lambda a:a['stack'][3].__setitem__('head','not-a-sha'))
    def test_gx05_head_drift(self):self.refuse(lambda a:a['stack'][4].__setitem__('head','f'*40))
    def test_gx05_branch_drift(self):self.refuse(lambda a:a['stack'][4].__setitem__('branch','feature/xbs-other'))
    def test_run_id_missing(self):self.refuse(lambda a:a['stack'][4]['runs'][0].__setitem__('id',0))
    def test_duplicate_run_id(self):self.refuse(lambda a:a['stack'][4]['runs'][1].__setitem__('id',a['stack'][4]['runs'][0]['id']))
    def test_wrong_workflow_id(self):self.refuse(lambda a:a['stack'][4]['runs'][0].__setitem__('workflow_id',1))
    def test_missing_api26_34_workflow(self):self.refuse(lambda a:a['stack'][4]['runs'].pop())
    def test_extra_api26_34_before_gx03(self):self.refuse(lambda a:a['stack'][0]['runs'].append(copy.deepcopy(a['stack'][4]['runs'][1])))
    def test_bad_main_sha(self):self.refuse(lambda a:a.__setitem__('main_at_inspection','not-sha'))
    def test_valid_original_count(self):
        out=validate_original_inventory(*original_fixture())
        self.assertEqual((out['original_tasks'],out['original_cases'],out['original_findings']),(178,131,26))
        self.assertFalse(out['original_case_acceptance_promoted'])
    def test_missing_original_task(self):
        l,m,t=original_fixture();l['phases'][5]['tasks'].pop()
        with self.assertRaises(Refused):validate_original_inventory(l,m,t)
    def test_duplicate_original_task(self):
        l,m,t=original_fixture();l['phases'][0]['tasks'][0]['id']=l['phases'][1]['tasks'][0]['id']
        with self.assertRaises(Refused):validate_original_inventory(l,m,t)
    def test_missing_case(self):
        l,m,t=original_fixture();m['cases'].pop()
        with self.assertRaises(Refused):validate_original_inventory(l,m,t)
    def test_duplicate_case(self):
        l,m,t=original_fixture();m['cases'][1]['id']=m['cases'][0]['id']
        with self.assertRaises(Refused):validate_original_inventory(l,m,t)
    def test_missing_finding(self):
        l,m,t=original_fixture();l['findings'].pop()
        with self.assertRaises(Refused):validate_original_inventory(l,m,t)
    def test_duplicate_finding(self):
        l,m,t=original_fixture();l['findings'][1]['id']=l['findings'][0]['id']
        with self.assertRaises(Refused):validate_original_inventory(l,m,t)
    def test_swapped_original_phase(self):
        l,m,t=original_fixture();l['phases'][1],l['phases'][2]=l['phases'][2],l['phases'][1]
        with self.assertRaises(Refused):validate_original_inventory(l,m,t)
    def test_missing_sixth_gx_gate(self):
        l,m,t=original_fixture();t['side_milestones'].pop()
        with self.assertRaises(Refused):validate_original_inventory(l,m,t)
    def test_wrong_tracker_count(self):
        l,m,t=original_fixture();t['original_task_count']=179
        with self.assertRaises(Refused):validate_original_inventory(l,m,t)
    def test_release_limits_positive(self):validate_release_limits(self.coverage)
    def test_release_flag_refusals(self):
        fields=['physical_root_qualified','kernel_su_qualified','signed_release_qualified',
             'battery_savings_measured','auto_merge_approved','original_cases_accepted']
        for key in fields:
            with self.subTest(key=key):
                row=copy.deepcopy(self.coverage);row[key]=True
                with self.assertRaises(Refused):validate_release_limits(row)
    def test_r10a_mislabeled_refused(self):
        row=copy.deepcopy(self.coverage);row['r10a']='APPLIED_R10'
        with self.assertRaises(Refused):validate_release_limits(row)
    def test_false_gx06_completion_refused(self):
        row=copy.deepcopy(self.coverage);row['gx06']='ACCEPTED'
        with self.assertRaises(Refused):validate_release_limits(row)
    def test_false_release_gate_refused(self):
        row=copy.deepcopy(self.coverage);row['release_gate']='ACCEPTED'
        with self.assertRaises(Refused):validate_release_limits(row)


# Additional integration tests verify the entrypoint used by the mandatory
# Go job, not merely independent fixture-policy functions.
import tempfile
from unittest import mock
from types import SimpleNamespace
from gx06_stack_gate import audit_local, GX06_BRANCH, GX05_TREE, GX05_BRANCH

class GX06AuditIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='gx06-local-read-only-')
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        for name in ('docs/engineering/XBS_GX06_STACK_LOCK.json',
                     'docs/engineering/XBS_GX06_COVERAGE.json',
                     'config/delivery-required-ci.json'):
            src=HERE/name;dest=self.root/name;dest.parent.mkdir(parents=True,exist_ok=True)
            dest.write_bytes(src.read_bytes())
            if name=='config/delivery-required-ci.json':
                policy=json.loads(dest.read_text())
                policy['base_branch']=GX05_BRANCH
                policy['required_workflows']=[w for w in policy['required_workflows'] if w['workflow_id'] != 418896455]
                dest.write_text(json.dumps(policy))
        ledger,matrix,tracker=original_fixture()
        for name,payload in [
            ('docs/engineering/XBS_IMPLEMENTATION_LEDGER.json',ledger),
            ('docs/engineering/XBS_TEST_MATRIX.json',matrix),
            ('docs/engineering/XBS_GX_TRACKER.json',tracker)]:
            f=self.root/name;f.parent.mkdir(parents=True,exist_ok=True)
            f.write_text(json.dumps(payload))
        self.head='f'*40;self.tree='e'*40
        self.changes=set(GX06_CHANGED_PATHS)
        self.wrong_parent=False
        self.gitcalls=[]

    def fakegit(self,root,*args):
        self.gitcalls.append(args)
        if args==('rev-parse','HEAD'):return self.head
        if args==('rev-parse','HEAD^{tree}'):return self.tree
        if args==('rev-parse','HEAD^'):return 'a'*40 if self.wrong_parent else GX05_HEAD
        if args==('rev-list','--parents','-n','1',self.head):return self.head+' '+('a'*40 if self.wrong_parent else GX05_HEAD)
        if args==('diff','--name-only','--no-renames','-z',GX05_HEAD,self.head):return '\0'.join(sorted(self.changes))+'\0'
        if args==('rev-parse',GX05_HEAD+'^{tree}'):return GX05_TREE
        if args==('symbolic-ref','--short','HEAD'):return GX06_BRANCH
        if args==('status','--porcelain','--untracked-files=all'):return ''
        if args==('diff','--name-only',GX05_HEAD,'HEAD'):return '\n'.join(sorted(self.changes))
        if args[0]=='rev-parse' and ':' in args[1]:return 'a'*40
        raise AssertionError('Unrecognized fake git invocation '+repr(args))

    def audit(self,env):
        with mock.patch('gx06_stack_gate.git',side_effect=self.fakegit),\
             mock.patch('stack_ancestry.git',side_effect=lambda root,*a:self.fakegit(root,*a).encode()),\
             mock.patch('gx06_stack_gate.subprocess.run',return_value=SimpleNamespace(returncode=0)):
            return audit_local(self.root,env=env)

    def test_local_named_feature_branch_and_counts(self):
        d=self.audit({})
        self.assertEqual(d['source_sha'],self.head)
        self.assertEqual((d['original_tasks'],d['original_cases'],d['original_findings']),(178,131,26))
        self.assertTrue(any(x[0]=='symbolic-ref' for x in self.gitcalls))
    def test_hosted_detached_head_allowed_only_with_proof(self):
        env={'GITHUB_ACTIONS':'true','GITHUB_EVENT_NAME':'pull_request',
             'GITHUB_HEAD_REF':GX06_BRANCH,'XBS_EXPECTED_HEAD_SHA':self.head,
             'GITHUB_RUN_ID':'1234','GITHUB_RUN_ATTEMPT':'2'}
        out=self.audit(env)
        self.assertEqual(out['github_run_attempt'],'2')
        self.assertFalse(any(x[0]=='symbolic-ref' for x in self.gitcalls))
    def test_hosted_wrong_head_refused(self):
        env={'GITHUB_ACTIONS':'true','GITHUB_EVENT_NAME':'pull_request',
             'GITHUB_HEAD_REF':'wrong','XBS_EXPECTED_HEAD_SHA':self.head,
             'GITHUB_RUN_ID':'1234','GITHUB_RUN_ATTEMPT':'2'}
        with self.assertRaises(Refused):self.audit(env)
    def test_missing_pr_run_attempt_refused(self):
        env={'GITHUB_ACTIONS':'true','GITHUB_EVENT_NAME':'pull_request',
             'GITHUB_HEAD_REF':GX06_BRANCH,'XBS_EXPECTED_HEAD_SHA':self.head,
             'GITHUB_RUN_ID':'1234','GITHUB_RUN_ATTEMPT':''}
        with self.assertRaises(Refused):self.audit(env)
    def test_unreviewed_parent_refused(self):
        self.wrong_parent=True
        with self.assertRaises(Refused):self.audit({})
    def test_unreviewed_path_refused(self):
        self.changes.add('evil.sh')
        with self.assertRaises(Refused):self.audit({})
    def test_incomplete_case_inventory_refused(self):
        f=self.root/'docs/engineering/XBS_TEST_MATRIX.json'
        d=json.loads(f.read_text());d['cases'].pop();f.write_text(json.dumps(d))
        with self.assertRaises(Refused):self.audit({})
    def test_policy_not_rebased_refused(self):
        f=self.root/'config/delivery-required-ci.json'
        d=json.loads(f.read_text());d['base_branch']='main';f.write_text(json.dumps(d))
        with self.assertRaises(Refused):self.audit({})
    def test_original_immutable_blob_changed_refused(self):
        old=self.fakegit
        def wrong(root,*args):
            if args==('rev-parse','HEAD:docs/engineering/XBS_TEST_MATRIX.json'):return 'b'*40
            return old(root,*args)
        with mock.patch('gx06_stack_gate.git',side_effect=wrong),\
             mock.patch('stack_ancestry.git',side_effect=lambda root,*a:self.fakegit(root,*a).encode()),\
             mock.patch('gx06_stack_gate.subprocess.run',return_value=SimpleNamespace(returncode=0)):
            with self.assertRaises(Refused):audit_local(self.root,env={})


if __name__=='__main__':unittest.main()
