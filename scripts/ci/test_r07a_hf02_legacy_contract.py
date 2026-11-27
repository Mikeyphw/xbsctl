#!/usr/bin/env python3
"""R07A HF02: source-bound compatibility after replacing mutable Actions tags.

Regression for failure test_r05a_source_contract.py::test_hosted_android_jvm_job_is_present.
Runs entirely offline, with negative mutations; never invokes Gradle or Android.
"""
from __future__ import annotations
import importlib.util
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
LEGACY=ROOT/'scripts/ci/test_r05a_source_contract.py'
WORKFLOW=ROOT/'.github/workflows/devtool-xbs-ci.yml'
POLICY=ROOT/'scripts/ci/r07a_policy.py'
PREFIX='gradle/actions/setup-gradle@'
EXPECTED=PREFIX+'ed408507eac070d1f99cc633dbcf757c94c7933a'
OLD=PREFIX+'v4'
ASSERTION="self.assertIn('"+EXPECTED+"',ci)"

class ContractRefused(AssertionError):pass

def validate(legacy:str,workflow:str,policy_sha:str)->None:
    if legacy.count(ASSERTION)!=1:
        raise ContractRefused('LEGACY_ASSERTION_NOT_SOURCE_PINNED')
    if OLD in legacy:
        raise ContractRefused('LEGACY_MUTABLE_ACTION_EXPECTATION')
    if workflow.count('uses: '+EXPECTED)!=3:
        raise ContractRefused('HOSTED_GRADLE_ACTION_PIN_DRIFT')
    if policy_sha!='ed408507eac070d1f99cc633dbcf757c94c7933a':
        raise ContractRefused('CANONICAL_POLICY_PIN_MISMATCH')
    if 'android-companion-jvm:' not in workflow or ':app:testDebugUnitTest' not in workflow:
        raise ContractRefused('JVM_GATE_MISSING')

class R07AHF02LegacyActionContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.legacy=LEGACY.read_text('utf-8')
        cls.workflow=WORKFLOW.read_text('utf-8')
        spec=importlib.util.spec_from_file_location('r07a_policy_hf02',POLICY)
        module=importlib.util.module_from_spec(spec)
        assert spec and spec.loader
        spec.loader.exec_module(module)
        cls.policy_sha=module.ACTIONS['gradle/actions/setup-gradle']

    def test_actual_legacy_contract_matches_pinned_workflow(self):
        validate(self.legacy,self.workflow,self.policy_sha)

    def test_original_mutable_reference_is_rejected(self):
        bad=self.legacy.replace(EXPECTED,OLD,1)
        with self.assertRaisesRegex(ContractRefused,'LEGACY_ASSERTION_NOT_SOURCE_PINNED'):
            validate(bad,self.workflow,self.policy_sha)

    def test_wrong_legacy_expected_sha_rejected(self):
        bad=self.legacy.replace(EXPECTED,PREFIX+'0'*40,1)
        with self.assertRaisesRegex(ContractRefused,'LEGACY_ASSERTION_NOT_SOURCE_PINNED'):
            validate(bad,self.workflow,self.policy_sha)

    def test_missing_assertion_is_rejected(self):
        bad=self.legacy.replace(ASSERTION,'# removed expectation',1)
        with self.assertRaisesRegex(ContractRefused,'LEGACY_ASSERTION_NOT_SOURCE_PINNED'):
            validate(bad,self.workflow,self.policy_sha)

    def test_mutable_workflow_ref_rejected(self):
        bad=self.workflow.replace('uses: '+EXPECTED,'uses: '+OLD,1)
        with self.assertRaisesRegex(ContractRefused,'HOSTED_GRADLE_ACTION_PIN_DRIFT'):
            validate(self.legacy,bad,self.policy_sha)

    def test_removed_gradle_action_rejected(self):
        bad=self.workflow.replace('uses: '+EXPECTED,'uses: gradle/actions/setup-gradle@'+('0'*40),1)
        with self.assertRaisesRegex(ContractRefused,'HOSTED_GRADLE_ACTION_PIN_DRIFT'):
            validate(self.legacy,bad,self.policy_sha)

    def test_canonical_policy_sha_mismatch_rejected(self):
        with self.assertRaisesRegex(ContractRefused,'CANONICAL_POLICY_PIN_MISMATCH'):
            validate(self.legacy,self.workflow,'0'*40)

    def test_legacy_ci_contracts_contain_no_obsolete_action_tags(self):
        old_tags=('actions/checkout@v4','actions/setup-go@v5',
                  'actions/setup-java@v4','actions/upload-artifact@v4',
                  'android-actions/setup-android@v3',
                  'gradle/actions/setup-gradle@v4',
                  'reactivecircus/android-emulator-runner@v2')
        # Only source-owned older contract tests; R07A negative tests deliberately mention old tags.
        names=('test_r05a_hf01_sdk_setup.py','test_r05a_source_contract.py',
               'test_r05c_hf01_emulator_ci.py','test_r05c_hf02_sdkmanager_bootstrap.py',
               'test_r05c_hf03_sdk_inventory.py','test_r05c_hf06_phase31_variant.py',
               'test_r05c_source_contract.py')
        for name in names:
            content=(ROOT/'scripts/ci'/name).read_text('utf-8')
            for tag in old_tags:
                with self.subTest(file=name,tag=tag):
                    self.assertNotIn(tag,content)

if __name__=='__main__': unittest.main(verbosity=2)
