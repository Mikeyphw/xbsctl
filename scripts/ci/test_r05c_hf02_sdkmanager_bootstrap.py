#!/usr/bin/env python3
"""R05C HF02: commandline SDK bootstrap must precede any sdkmanager call.

Offline source test for the GitHub Actions API36 instrumentation job. No SDK required.
"""
from pathlib import Path
import re
import unittest

WORKFLOW = Path(__file__).resolve().parents[2] / '.github/workflows/devtool-xbs-ci.yml'


def api36(text):
    jobs = re.findall(r'(?m)^  [a-z][a-z0-9-]*:\s*$', text)
    if len(jobs) != len(set(jobs)):
        raise ValueError('Ambiguous repeated job')
    start = '  android-companion-api36:\n'
    stop = '\n  android-companion-debug:'
    if text.count(start) != 1 or text.count(stop) != 1:
        raise ValueError('Required job boundary is missing or repeated')
    return text.split(start, 1)[1].split(stop, 1)[0]


class SdkmanagerBootstrapRegression(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = WORKFLOW.read_text('utf-8')
        cls.job = api36(cls.workflow)

    def test_sdkmanager_installed_before_first_invocation(self):
        start = self.job.index('      - name: Initialize Android SDK command-line tools for API36\n')
        setup = self.job.index('        uses: android-actions/setup-android@v3\n', start)
        install = self.job.index('      - name: Install Android API36 build-tools 36.0.0\n')
        first_sdk = self.job.index('          sdkmanager \'build-tools;36.0.0\'')
        self.assertLess(start, setup)
        self.assertLess(setup, install)
        self.assertLess(install, first_sdk)
        self.assertEqual(self.job.count('uses: android-actions/setup-android@v3'), 1)
        self.assertEqual(self.job.count("sdkmanager 'build-tools;36.0.0'"), 1)
        self.assertNotRegex(self.job[:setup], r'(?m)^\s+(?:run: )?sdkmanager\b')

    def test_legacy_tools_package_never_requested(self):
        setup = self.job.split('uses: android-actions/setup-android@v3', 1)[1]
        self.assertTrue(setup.startswith("\n        with:\n          packages: 'platform-tools'\n"))
        self.assertNotRegex(setup.split('      - name:', 1)[0], r'(?m)^\s*packages:\s*[\'\"]?tools(?:\s|[\'\"]|$)')

    def test_build_tools_and_apksigner_presence_are_checked(self):
        section = self.job.split('      - name: Install Android API36 build-tools 36.0.0\n',1)[1].split('      - name:',1)[0]
        self.assertIn('run: |\n          set -euo pipefail\n',section)
        self.assertIn('command -v sdkmanager',section)
        self.assertIn("sdkmanager 'build-tools;36.0.0'",section)
        self.assertIn('test -x "$ANDROID_HOME/build-tools/36.0.0/apksigner"',section)

    def test_emulator_and_receipt_verification_still_required(self):
        self.assertIn('needs: [go-contract, android-companion-jvm]',self.job)
        self.assertIn('reactivecircus/android-emulator-runner@v2',self.job)
        self.assertIn('connectedDebugAndroidTest',self.job)
        self.assertIn('r05c_verify_instrumentation.py',self.job)
        self.assertIn('test "$HEAD_SHA" =',self.job)
        self.assertIn('if-no-files-found: error',self.job)
        self.assertNotIn('continue-on-error',self.job)
        self.assertNotIn('|| true',self.job)

    def test_shell_and_kvm_repairs_kept(self):
        self.assertIn('sudo chmod 0666 /dev/kvm',self.job)
        script = self.job.split('          script: |\n',1)[1].split('\n      - name:',1)[0]
        self.assertTrue(script.startswith('            set -eu\n'))
        self.assertNotIn('pipefail',script)

    def test_existing_sdk_jobs_unchanged_and_head_pins_retained(self):
        self.assertEqual(self.workflow.count('ref: ${{ github.event.pull_request.head.sha || github.sha }}'),4)
        for name in ['android-companion-jvm','android-companion-debug']:
            block = self.workflow.split('  '+name+':\n',1)[1]
            next_job = re.search(r'(?m)^  [a-z][a-z0-9-]*:\s*$', block)
            if next_job: block = block[:next_job.start()]
            self.assertIn("packages: 'platform-tools'",block)
            self.assertIn("'build-tools;36.0.0'",block)

if __name__ == '__main__':
    unittest.main(verbosity=2)
