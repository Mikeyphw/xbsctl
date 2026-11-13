#!/usr/bin/env python3
"""R06B fail-closed Devtool hosted-runtime source and receipt regressions.

Synthetic JSON is never claimed to be runtime qualification. Hosted job executes
actual pinned Devtool wheel independently via r06b_devtool_qualification.py.
"""
from __future__ import annotations
import copy
import importlib.util
from pathlib import Path
import unittest
import tomllib

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
MODULE=HERE/'r06b_devtool_qualification.py'
SPEC=importlib.util.spec_from_file_location('xbs_r06b_runtime',MODULE)
assert SPEC and SPEC.loader
D=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(D)

class DevtoolRuntimeContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest=(ROOT/'.devtool.toml').read_text(encoding='utf-8')
        cls.workflow=(ROOT/'.github/workflows/devtool-xbs-ci.yml').read_text(encoding='utf-8')
        D.validate_manifest(cls.manifest)
    @staticmethod
    def plan():
        keys={name:'xbs.ci-module.'+name for name in D.REQUIRED}
        nodes=[{'id':name,'key':keys[name],'ref':ref,
                'depends_on':[keys[d] for d in deps]}
               for name,(ref,deps) in D.REQUIRED.items()]
        commands=[{'workflow_ref':ref,'argv':argv} for ref,argv in [
            ('job:xbs-go-mod-present',['test','-f','go.mod']),
            ('job:xbs-go-test',['go','test','-json','-p','2','./...']),
            ('job:xbs-go-vet',['go','vet','./...']),
            ('job:xbs-contract-check',['go','run','./cmd/xbs','contracts','check']),
            ('job:xbs-final-seal',['go','run','./cmd/xbsrepo','final-seal']),
            ('job:xbs-build-module',['go','run','./cmd/xbsrepo','build-module','--out','dist/XtremeBS-go.zip'])]]
        return {'schema_version':2,'action':'ci-module','targets':['xbs'],
                'graph':{'nodes':nodes,'order':[keys[name] for name in D.REQUIRED]},
                'commands':commands}
    def test_actual_source_manifest_policy(self):
        D.validate_manifest(self.manifest)
    def test_ci_contains_pinned_readonly_runtime_gate(self):
        expected='python3 -B scripts/ci/r06b_devtool_qualification.py hosted --repo .'
        self.assertEqual(self.workflow.count(expected),1)
        self.assertIn('XBS_EXPECTED_HEAD_SHA: ${{ github.event.pull_request.head.sha || github.sha }}',self.workflow)
        self.assertIn('      - name: Go race detector\n        run: go test -race ./internal/config ./internal/control ./internal/daemon',self.workflow)
        self.assertIn('r05c_full_ci_precommit.py --run',self.workflow)
        self.assertIn('connectedDebugAndroidTest',self.workflow)
        self.assertIn('if-no-files-found: error',self.workflow)
        self.assertNotIn('continue-on-error',self.workflow)
        self.assertNotIn('|| true',self.workflow)
    def test_upstream_devtool_source_sha_exact(self):
        self.assertEqual(D.DEVTOOL_SHA,'44eb17bbbd63194aa67cde0f7b63d5ada44fb96e')
        self.assertEqual(D.DEVTOOL_VERSION,'1.1.0')
    def test_valid_synthetic_schema(self):
        self.assertEqual(D.validate_plan(self.plan())['graph_nodes'],6)
    def test_missing_native_test_ref_refused(self):
        p=self.plan();p['commands'][1]['workflow_ref']='job:fake-test'
        with self.assertRaises(D.Refused):D.validate_plan(p)
    def test_legacy_unstructured_go_test_refused(self):
        p=self.plan();p['commands'][1]['argv']=['go','test','./...']
        with self.assertRaises(D.Refused):D.validate_plan(p)
    def test_graph_missing_vet_refused(self):
        p=self.plan();p['graph']['nodes']=[n for n in p['graph']['nodes'] if n['id']!='vet']
        with self.assertRaises(D.Refused):D.validate_plan(p)
    def test_graph_bypassed_contract_refused(self):
        p=self.plan();next(n for n in p['graph']['nodes'] if n['id']=='module')['depends_on']=[]
        with self.assertRaises(D.Refused):D.validate_plan(p)
    def test_graph_reordering_fails(self):
        p=self.plan();p['graph']['order'].reverse()
        with self.assertRaises(D.Refused):D.validate_plan(p)
    def test_go_plan_must_not_invoke_android_or_root(self):
        for bad in ['gradle','adb','su','ksud','magisk']:
            with self.subTest(bad=bad):
                p=self.plan();p['commands'][0]['argv']=['sh','-c',bad+' --version']
                with self.assertRaises(D.Refused):D.validate_plan(p)
    def test_preflight_healthy(self):
        self.assertTrue(D.validate_preflight({'healthy':True,'targets':['xbs'],'runners':['go','command'],'checks':[]})['healthy'])
    def test_preflight_bad_or_missing_runtime_refused(self):
        for p in ({'healthy':False,'targets':['xbs'],'runners':['go']},
                  {'healthy':True,'targets':['xbs'],'runners':['command']},
                  {'healthy':True,'targets':[],'runners':['go']}):
            with self.assertRaises(D.Refused):D.validate_preflight(p)
    def test_affected_three_ownership_cases(self):
        for path,target,uncertain in [
            ('cmd/r06b-fixture.go',{'xbs','xbs_android'},False),
            ('android-app/app/r06b-fixture.txt',{'xbs_android'},False),
            ('docs/r06b-unknown.txt',{'xbs','xbs_android'},True)]:
            p={'schema_version':1,'changed_paths':[path],'targets':list(target),
               'uncertain_paths':[path] if uncertain else []}
            self.assertEqual(set(D.validate_affected(p,changed=path,expected=target,require_uncertain=uncertain)['targets']),target)
    def test_affected_missing_android_dependency_refused(self):
        p={'schema_version':1,'changed_paths':['cmd/a.go'],'targets':['xbs'],'uncertain_paths':[]}
        with self.assertRaises(D.Refused):D.validate_affected(p,changed='cmd/a.go',expected={'xbs','xbs_android'})
    def test_affected_unknown_fallback_required(self):
        p={'schema_version':1,'changed_paths':['docs/new.txt'],'targets':['xbs','xbs_android'],'uncertain_paths':[]}
        with self.assertRaises(D.Refused):D.validate_affected(p,changed='docs/new.txt',expected={'xbs','xbs_android'},require_uncertain=True)
    def test_root_ownership_downgrade_refused(self):
        altered=self.manifest.replace('affected_ownership = "inputs-only"','affected_ownership = "root-and-inputs"',1)
        with self.assertRaises(D.Refused):D.validate_manifest(altered)
    def test_missing_cross_target_inputs_refused(self):
        altered=self.manifest.replace('"../cmd/**"','"../not-the-cmd/**"',1)
        with self.assertRaises(D.Refused):D.validate_manifest(altered)
    def test_ci_is_not_mocked_green(self):
        source=MODULE.read_text()
        self.assertIn("def run_native(py:Path,root:Path,manifest:str,tmp:Path)->dict:",source)
        self.assertIn("def verify_provenance(py:Path,wheel:Path,devtool_source:Path,expected_repo:Path)->dict:",source)
        self.assertIn("'git','-C'",source)
        self.assertIn("'wheel','--no-deps','--no-build-isolation','--no-index'",source)
        self.assertIn('DEVTOOL_FETCHED_SHA_WRONG',source)
        self.assertIn('HOSTED_CHECKOUT_HEAD_SHA_MISMATCH',source)
        self.assertIn("'physical_root_qualified':False",source)

if __name__=='__main__':unittest.main(verbosity=2)
