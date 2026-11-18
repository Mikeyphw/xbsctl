#!/usr/bin/env python3
"""R08/R10 fail-closed source, evidence, and merge-compatibility contracts."""
from __future__ import annotations
import copy
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
import json
from r08_r10_acceptance import (
    Refused, validate_mapping, validate_native_test_source,
    validate_gradle_merge_compatibility, audit_source, METHODS,
)
ROOT = Path(__file__).resolve().parents[2]
MAP = ROOT / 'docs/engineering/XBS_R08_R10_EVIDENCE_MAP.json'

def fixture_sources():
    java='\n'.join('@Test public void '+method+'() {}' for method in METHODS)
    host=("gradle_test('io.github.mikeceead.xbscompanion.Gx04TilesInstrumentedTest')\n"
          "report['gx04_tile_assertions']=junit_receipt(RESULTS,{\n"+
          '\n'.join("'"+method+"'" for method in METHODS))
    flow='          fetch-depth: 24\npython3 -B scripts/ci/test_r08_r10_acceptance.py\n'
    gradle="""tasks.register('verifyStableToolchainBaseline') {
    doLast {
        def devtoolManifest = rootProject.file('../.devtool.toml')
        if (!devtoolManifest.isFile()) throw new GradleException('Standalone XBS Devtool manifest missing at repository root: ' + devtoolManifest)
        def rootBuild = rootProject.file('build.gradle').text
        def devtool = rootProject.file('../.devtool.toml').text
    }
}
"""
    return java,host,flow,gradle

class R08R10AcceptanceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mapping=json.loads(MAP.read_text())

    def test_01_expected_six_real_methods(self):
        self.assertEqual(len(METHODS),6)
        self.assertEqual(len(set(METHODS)),6)

    def test_02_mapping_positive(self):validate_mapping(self.mapping)

    def refuse_map(self,change):
        data=copy.deepcopy(self.mapping)
        change(data)
        with self.assertRaises(Refused):validate_mapping(data)

    def test_03_false_original_case_acceptance_refused(self):
        self.refuse_map(lambda x:x['original_cases_accepted'].append('EM-019'))

    def test_04_false_physical_claim_refused(self):
        self.refuse_map(lambda x:x.__setitem__('physical_root_qualified',True))

    def test_05_false_signed_release_refused(self):
        self.refuse_map(lambda x:x.__setitem__('signed_release_qualified',True))

    def test_06_false_aggregate_ci_refused(self):
        self.refuse_map(lambda x:x.__setitem__('aggregate_ci_qualified',True))

    def test_07_missing_assertion_refused(self):
        self.refuse_map(lambda x:x['required_api36_methods'].pop())

    def test_08_duplicate_assertion_refused(self):
        self.refuse_map(lambda x:x['required_api36_methods'].__setitem__(1,x['required_api36_methods'][0]))

    def test_09_unknown_assertion_refused(self):
        self.refuse_map(lambda x:x['candidate_assertions']['EM-019'].append('neverExecuted'))

    def test_10_original_case_id_drift_refused(self):
        self.refuse_map(lambda x:x['emulator_candidate_cases'].__setitem__(0,'EM-001'))

    def test_11_r08_task_loss_refused(self):
        self.refuse_map(lambda x:x['r08_original_tasks'].pop())

    def test_12_original_case_count_drift_refused(self):
        self.refuse_map(lambda x:x.__setitem__('original_cases_preserved',130))

    def test_13_method_and_host_positive(self):
        java,host,flow,_=fixture_sources()
        validate_native_test_source(java,host,flow)

    def test_14_missing_real_android_method_refused(self):
        java,host,flow,_=fixture_sources()
        with self.assertRaises(Refused):validate_native_test_source(java.replace('public void '+METHODS[0], 'public void missing'),host,flow)

    def test_15_host_only_assertion_not_enough(self):
        java,host,flow,_=fixture_sources()
        with self.assertRaises(Refused):validate_native_test_source(java,host.replace("'"+METHODS[2]+"'", "'different'"),flow)

    def test_16_fabricated_test_double_refused(self):
        java,host,flow,_=fixture_sources()
        with self.assertRaises(Refused):validate_native_test_source(java+'\n@Test public void '+METHODS[0]+'() {}',host,flow)

    def test_17_missing_real_junit_receipt_refused(self):
        java,host,flow,_=fixture_sources()
        with self.assertRaises(Refused):validate_native_test_source(java,host.replace("report['gx04_tile_assertions']=junit_receipt(RESULTS", "report['gx04_tile_assertions']={"),flow)

    def test_18_hosted_source_negatives_required(self):
        java,host,flow,_=fixture_sources()
        with self.assertRaises(Refused):validate_native_test_source(java,host,flow.replace('python3 -B scripts/ci/test_r08_r10_acceptance.py','true'))

    def test_19_continue_on_error_refused(self):
        java,host,flow,_=fixture_sources()
        with self.assertRaises(Refused):validate_native_test_source(java,host,flow+'continue-on-error: true')

    def test_20_gradle_merge_compatibility_positive(self):
        validate_gradle_merge_compatibility(fixture_sources()[-1])

    def test_21_unchecked_missing_manifest_refused(self):
        gradle=fixture_sources()[-1]
        with self.assertRaises(Refused):validate_gradle_merge_compatibility(gradle.replace("if (!devtoolManifest.isFile())", "if (false)"))

    def test_22_old_devtool_path_refused(self):
        gradle=fixture_sources()[-1]
        with self.assertRaises(Refused):validate_gradle_merge_compatibility(gradle.replace("../.devtool.toml", "../../../.devtool.toml"))

    def test_23_guard_after_read_refused(self):
        gradle=fixture_sources()[-1]
        before='        def devtoolManifest = rootProject.file(\'../.devtool.toml\')\n        if (!devtoolManifest.isFile()) throw new GradleException(\'Standalone XBS Devtool manifest missing at repository root: \' + devtoolManifest)\n'
        after=before.strip().replace('        def','        def')+'\n'
        self.assertIn(before,gradle)
        with self.assertRaises(Refused):validate_gradle_merge_compatibility(gradle.replace(before,'').replace('        def devtool = rootProject.file(\'../.devtool.toml\').text\n','        def devtool = rootProject.file(\'../.devtool.toml\').text\n'+before))

    def test_24_actual_source_gate_on_complete_fixture(self):
        # Exact source gate, not only the isolated validator functions.
        from r08_r10_acceptance import JAVA, HOST, WORKFLOW, GRADLE, MAP, POLICY
        java,host,flow,gradle=fixture_sources()
        with tempfile.TemporaryDirectory() as td:
            base=Path(td)
            sources={JAVA:java,HOST:host,WORKFLOW:flow,GRADLE:gradle,
                     MAP:json.dumps(self.mapping), POLICY:json.dumps({
                         'repository':'Mikeyphw/xbsctl',
                         'base_branch':'feature/xbs-gx06-stack-integration-20261118',
                         'event':'pull_request','release_qualification':'separate',
                         'required_workflows':[
                          {'name':'Devtool XBS Go and Android candidates','workflow_id':386774409},
                          {'name':'XBS GX03 hosted API26 / API34 compatibility','workflow_id':419253895}]
                     })}
            for relative,content in sources.items():
                file=base/relative;file.parent.mkdir(parents=True,exist_ok=True)
                file.write_text(content)
            self.assertEqual(audit_source(base)['required_instrumentation'],6)

    def test_25_original_source_is_not_silently_updated(self):
        self.assertEqual(self.mapping['base_source_sha'],
                         '5287f4a41eedc2b7fa3a33c6af820f117db78b84')
        self.assertEqual(self.mapping['original_cases_accepted'],[])

    def test_26_independent_merge_conflict_resolution_fixture(self):
        # Diagnostic git merge-file only on temporary in-memory fixtures;
        # never performs Git merge, cherry-pick, reset or repository mutation.
        old='a\nb\nc\nd\n    doLast {\n        def rootBuild = test\n        def devtool = rootProject.file(\'../../../.devtool.toml\').text\n        def prerelease = test\n'
        main=old.replace("../../../.devtool.toml", "../.devtool.toml")
        ours=old.replace("    doLast {\n", "    doLast {\n        def devtoolManifest = rootProject.file('../.devtool.toml')\n        if (!devtoolManifest.isFile()) throw new GradleException('Standalone XBS Devtool manifest missing at repository root: ' + devtoolManifest)\n").replace("../../../.devtool.toml", "../.devtool.toml")
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp)
            for name,data in [('old',old),('main',main),('ours',ours)]:
                (folder/name).write_text(data)
            merged=subprocess.run(['git','merge-file','-p',str(folder/'ours'),str(folder/'old'),str(folder/'main')],capture_output=True,text=True,timeout=10)
            self.assertEqual(merged.returncode,0,merged.stdout)
            self.assertNotIn('<<<<<<<',merged.stdout)
            self.assertIn("def devtool = rootProject.file('../.devtool.toml').text",merged.stdout)
            self.assertIn('if (!devtoolManifest.isFile())',merged.stdout)

if __name__ == '__main__':unittest.main(verbosity=2)
