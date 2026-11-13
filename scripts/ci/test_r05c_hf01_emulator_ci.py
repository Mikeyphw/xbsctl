#!/usr/bin/env python3
"""R05C HF01 CI guard: POSIX emulator-runner script, accessible KVM, build tools.

Uses only standard Python and intentionally rejects a green gate with skipped tests.
"""
from pathlib import Path
import re
import unittest

ROOT=Path(__file__).resolve().parents[2]
WORKFLOW=ROOT/'.github/workflows/devtool-xbs-ci.yml'


def block(text):
    marker='  android-companion-api36:\n'
    if text.count(marker)!=1 or text.count('  android-companion-debug:\n')!=1:
        raise ValueError('missing or ambiguous Android instrumentation job')
    return text.split(marker,1)[1].split('\n  android-companion-debug:',1)[0]


class EmulatorHostedRegression(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw=WORKFLOW.read_text(encoding='utf-8')
        cls.job=block(cls.raw)

    def test_posix_script_is_not_silently_executed_by_bash(self):
        self.assertEqual(self.job.count('uses: reactivecircus/android-emulator-runner@v2'),1)
        self.assertEqual(self.job.count('          script: |\n'),1)
        contents=self.job.split('          script: |\n',1)[1].split('\n      - name:',1)[0]
        self.assertTrue(contents.startswith('            set -eu\n'))
        self.assertNotIn('pipefail',contents)
        self.assertIn('connectedDebugAndroidTest',contents)
        self.assertIn('r05c_verify_instrumentation.py',contents)
        self.assertIn('test -s "$APK"',contents)
        self.assertIn('apksigner verify "$APK"',contents)
        self.assertIn('SOURCE_SHA',contents)

    def test_kvm_acceleration_preflight_before_runner(self):
        pre=self.job.split('      - name: Android API36 rootless instrumentation',1)[0]
        self.assertEqual(pre.count('      - name: Enable KVM for API36 emulator'),1)
        self.assertIn('test -c /dev/kvm',pre)
        self.assertIn('sudo chmod 0666 /dev/kvm',pre)
        self.assertIn('test -w /dev/kvm',pre)
        self.assertNotIn('|| true',pre)

    def test_supported_sdk_configuration_no_silently_ignored_inputs(self):
        section=self.job.split('uses: reactivecircus/android-emulator-runner@v2',1)[1].split('          script: |',1)[0]
        self.assertNotRegex(section,r'(?m)^\s+build-tools:')
        self.assertIn("sdkmanager 'build-tools;36.0.0'",self.job)
        self.assertIn('api-level: 36',section)
        self.assertIn('target: google_apis',section)
        self.assertIn('arch: x86_64',section)

    def test_rootless_mandatory_gate_and_source_binding(self):
        self.assertIn('needs: [go-contract, android-companion-jvm]',self.job)
        self.assertNotIn('continue-on-error',self.job)
        self.assertNotIn('workflow_dispatch',self.job)
        self.assertIn('test "$HEAD_SHA" =',self.job)
        self.assertIn('if-no-files-found: error',self.job)
        self.assertEqual(self.raw.count('ref: ${{ github.event.pull_request.head.sha || github.sha }}'),4)


if __name__=='__main__': unittest.main(verbosity=2)
