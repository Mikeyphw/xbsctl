#!/usr/bin/env python3
"""HF05: test live CLI construction and native affected workspace scoping.

Synthetic envelope fixtures test the data boundary; no claimed hosted run here.
"""
from __future__ import annotations
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import tomllib

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
SPEC=importlib.util.spec_from_file_location('xbs_r06b_hf05',HERE/'r06b_devtool_qualification.py')
assert SPEC and SPEC.loader
D=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(D)

class HF05NativeAffectedScope(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source=(HERE/'r06b_devtool_qualification.py').read_text()
        cls.manifest=(ROOT/'.devtool.toml').read_text()
        cls.fixture=json.loads((HERE/'fixtures/r06b_devtool_envelopes.json').read_text())['envelopes']

    def test_default_target_not_workflow_wide_selection(self):
        manifest=tomllib.loads(self.manifest)
        self.assertEqual(manifest['workspace']['default_target'],'xbs')
        self.assertEqual(set(manifest['workspace']['groups']['all']),{'xbs','xbs_android'})
        D.validate_manifest(self.manifest)

    def test_actual_command_explicitly_overrides_default_target(self):
        self.assertEqual(self.source.count("'affected','--all-targets',"),1)
        self.assertIn('def validate_affected_scope(data:dict)->dict:',self.source)
        self.assertIn('scope=validate_affected_scope(data)',self.source)
        self.assertIn('require_uncertain=unknown',self.source)

    def test_full_two_target_candidate_scope(self):
        self.assertEqual(D.validate_affected_scope({'candidates':['xbs','xbs_android']})['candidate_targets'],
                         ['xbs','xbs_android'])
        for candidates in ([],['xbs'],['xbs_android'],['xbs','xbs'],
                           ['xbs','xbs_android','wrong'],None,'xbs',42,[True,'xbs']):
            with self.subTest(candidates=candidates):
                with self.assertRaisesRegex(D.Refused,'AFFECTED_CANDIDATE_SCOPE_WRONG'):
                    D.validate_affected_scope({'candidates':candidates})

    def test_native_run_real_code_path_three_cases(self):
        self.verify_runtime(fault=None)

    def test_native_run_refuses_default_target_only(self):
        with self.assertRaisesRegex(D.Refused,'AFFECTED_CANDIDATE_SCOPE_WRONG'):
            self.verify_runtime(fault='scope')

    def test_native_run_refuses_missing_shared_android(self):
        with self.assertRaisesRegex(D.Refused,'AFFECTED_TARGET_SELECTION_WRONG'):
            self.verify_runtime(fault='shared')

    def test_native_run_refuses_unknown_path_downgrade(self):
        with self.assertRaises(D.Refused):
            self.verify_runtime(fault='unknown')

    def verify_runtime(self,*,fault):
        changed=['cmd/r06b-fixture.go','android-app/app/r06b-fixture.txt','docs/r06b-unknown.txt']
        wanted=[['xbs','xbs_android'],['xbs_android'],['xbs','xbs_android']]
        invocations=[]
        def fake_fixture(dest,manifest,path):
            self.assertIn(path,changed)
            dest.mkdir(parents=True,exist_ok=True)
            return 'a'*40,'b'*40
        def fake_invoke(argv,*,cwd,timeout=120,env=None):
            argv=list(map(str,argv))
            invocations.append(argv)
            if 'plan' in argv:
                if '--explain' in argv:return 'module depends on seal\n'
                return json.dumps(self.fixture['PLAN'])
            if 'preflight' in argv:return json.dumps(self.fixture['PREFLIGHT'])
            self.assertIn('affected',argv)
            self.assertIn('--all-targets',argv)
            i=len([x for x in invocations if 'affected' in x])-1
            response=copy.deepcopy(self.fixture['AFFECTED'])
            row=response['data']
            row['candidates']=['xbs','xbs_android']
            row['changed_paths']=[changed[i]]
            row['targets']=wanted[i]
            row['uncertain_paths']=[changed[i]] if i==2 else []
            if fault=='scope' and i==0:
                row['candidates']=['xbs'];row['targets']=['xbs']
            if fault=='shared' and i==0:row['targets']=['xbs']
            if fault=='unknown' and i==2:row['uncertain_paths']=[]
            return json.dumps(response)
        with tempfile.TemporaryDirectory() as tmp:
            with mock.patch.object(D,'invoke',side_effect=fake_invoke),\
                 mock.patch.object(D,'fixture',side_effect=fake_fixture):
                result=D.run_native(Path('/test/python3'),ROOT,self.manifest,Path(tmp))
        if fault is None:
            self.assertEqual(result['graph_nodes'],6)
            self.assertEqual(len(result['affected']),3)
            self.assertEqual([item['candidate_targets'] for item in result['affected']],
                             [['xbs','xbs_android']]*3)
            self.assertEqual(len(result['native_json_envelopes']),5)
            self.assertEqual(len([x for x in invocations if '--all-targets' in x]),3)

    def test_existing_hosted_gates_are_untouched(self):
        wf=(ROOT/'.github/workflows/devtool-xbs-ci.yml').read_text()
        self.assertIn('needs: [go-contract, android-companion-jvm]',wf)
        self.assertIn('connectedDebugAndroidTest',wf)
        self.assertIn('r05c_verify_instrumentation.py --results',wf)
        self.assertIn('r06b_devtool_qualification.py count-go --repo .',wf)
        self.assertIn('r06b_devtool_qualification.py count-jvm --repo .',wf)
        self.assertIn('R06B source-pinned Devtool runtime qualification',wf)

if __name__=='__main__':unittest.main(verbosity=2)
