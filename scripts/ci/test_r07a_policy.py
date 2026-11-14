#!/usr/bin/env python3
"""Adversarial checks for the *real* R07A policy guard and source workflow."""
from __future__ import annotations
import unittest
from pathlib import Path
import importlib.util

ROOT=Path(__file__).resolve().parents[2]
SOURCE=ROOT/'.github/workflows/devtool-xbs-ci.yml'
SPEC=importlib.util.spec_from_file_location('r07a_policy',Path(__file__).with_name('r07a_policy.py'))
P=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(P)

class Policy(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source=SOURCE.read_text()
        cls.known=P.guard(cls.source)

    def negative(self,old,new,reason):
        self.assertIn(old,self.source)
        with self.assertRaisesRegex(P.Refused,reason):
            P.guard(self.source.replace(old,new,1))

    def test_real_workflow_and_action_inventory(self):
        self.assertEqual(self.known['required_jobs'],3)
        self.assertEqual(P.audit(ROOT)['checkout_head_pins'],4)

    def test_trigger_push_is_not_an_extra_job(self):
        # Regress the actual failure from the first R07A execution:
        # ``on.push`` must never be counted as a job.
        self.assertIn('on:\n  push:\n',self.source)
        blocks=P.sections(self.source)
        self.assertEqual(set(blocks),set(P.REQUIRED)|{P.OPTIONAL})
        self.assertNotIn('push',blocks)
        self.assertNotIn('pull_request',blocks)

    def test_job_boundaries_ignore_other_top_level_mappings(self):
        example=('name: example\non:\n  push:\n    branches: [main]\n'
                 '  pull_request:\npermissions:\n  contents: read\n'
                 'jobs:\n  first:\n    steps:\n'
                 '      - run: echo hi\n  second:\n    steps:\n'
                 '      - run: echo bye\n')
        self.assertEqual(set(P.sections(example)),{'first','second'})
        self.assertNotIn('push',P.sections(example))
        with self.assertRaisesRegex(P.Refused,'JOBS_SECTION_MISSING_OR_DUPLICATE'):
            P.sections(example.replace('jobs:\n',''))
        with self.assertRaisesRegex(P.Refused,'JOBS_SECTION_MISSING_OR_DUPLICATE'):
            P.sections(example+'jobs:\n  third:\n    steps:\n')

    def test_duplicate_job_versus_event_keys(self):
        example=('on:\n  push:\n  pull_request:\n'
                 'jobs:\n  go-contract:\n    runs-on: ubuntu-latest\n'
                 '  go-contract:\n    runs-on: ubuntu-latest\n')
        with self.assertRaisesRegex(P.Refused,'DUPLICATE_JOB_IDS'):
            P.sections(example)


    def test_mutable_action_refused(self):
        self.negative('actions/checkout@'+P.ACTIONS['actions/checkout'], 'actions/checkout@v4','MUTABLE_ACTION_REFERENCE')

    def test_wrong_full_action_sha_refused(self):
        self.negative('actions/checkout@'+P.ACTIONS['actions/checkout'], 'actions/checkout@'+'0'*40,'ACTION_SHA_DRIFT')

    def test_unknown_new_action_refused(self):
        self.negative('      - uses: actions/checkout@', '      - uses: unknown-company/unknown@','UNREVIEWED_ACTION')

    def test_missing_go_count_gate_refused(self):
        self.negative('      - name: R06B HF03 actual Go test case counts\n','      - name: Deleted Go counts\n','REQUIRED_STEP_MISSING')

    def test_missing_jvm_receipt_refused(self):
        self.negative('android-evidence/r06b-jvm-test-counts.json','android-evidence/removed.json','JVM_RECEIPT_MISSING')

    def test_api36_cannot_be_optional(self):
        self.negative('    needs: [go-contract, android-companion-jvm]', '    needs: []','API36_DEPENDENCIES_WEAKENED')

    def test_workflow_cannot_hide_missing_artifact(self):
        self.negative('          if-no-files-found: error','          if-no-files-found: warn','MODULE_ARTIFACT_FAIL_OPEN')

    def test_wrong_pr_checkout_sha_refused(self):
        self.negative('ref: ${{ github.event.pull_request.head.sha || github.sha }}','ref: main','CHECKOUT_SHA_BOUNDARY_MISSING')

    def test_no_conditional_required_job(self):
        self.negative('  android-companion-api36:\n', '  android-companion-api36:\n    if: false\n','CONDITIONAL_REQUIRED_GATE')

    def test_no_silent_success(self):
        self.negative('          set -euo pipefail','          set -euo pipefail\n          false || true','FAIL_OPEN_SHELL')

    def test_no_duplicate_or_extra_workflow_job(self):
        with self.assertRaises(P.Refused):P.guard(self.source+'\n  rogue-job:\n    runs-on: ubuntu-latest\n')

    def test_source_sha_hosted_policy_negative(self):
        import os,unittest.mock
        with unittest.mock.patch.dict(os.environ,{'GITHUB_ACTIONS':'true','XBS_EXPECTED_HEAD_SHA':'0'*40}):
            with self.assertRaisesRegex(P.Refused,'GITHUB_HEAD_SHA_MISMATCH'):
                P.audit(ROOT,hosted=True)

if __name__=='__main__':unittest.main(verbosity=2)
