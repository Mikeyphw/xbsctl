#!/usr/bin/env python3
"""R05A HF01: fail CI before SDK bootstrap when retired `tools` is requested.

Uses only the Python standard library; remains runnable without Android SDK or Gradle.
"""
from pathlib import Path
import re
import unittest

WORKFLOW = Path(__file__).resolve().parents[2] / '.github/workflows/devtool-xbs-ci.yml'
ACTIONS = ('android-companion-jvm', 'android-companion-debug')
EMULATOR = 'android-companion-api36'
SDK_JOBS = ACTIONS + (EMULATOR,)
ACTION = 'android-actions/setup-android@v3'


def job_blocks(text):
    lines = list(re.finditer(r'(?m)^  ([a-z][a-z0-9-]*):\s*$', text))
    return {
        m.group(1): text[m.end(): (lines[i + 1].start() if i + 1 < len(lines) else len(text))]
        for i, m in enumerate(lines)
    }


class AndroidSdkBootstrapTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = WORKFLOW.read_text(encoding='utf-8')
        cls.jobs = job_blocks(cls.workflow)

    def test_each_android_job_sets_supported_sdk_package(self):
        total = 0
        for name in SDK_JOBS:
            with self.subTest(job=name):
                job = self.jobs.get(name)
                self.assertIsNotNone(job, f'Missing Android job: {name}')
                lines = job.splitlines()
                positions = [i for i, line in enumerate(lines) if re.fullmatch(r'\s*(?:-\s*)?uses:\s*' + re.escape(ACTION), line)]
                self.assertEqual(len(positions), 1, f'Expected one SDK setup in {name}')
                pos = positions[0]
                if lines[pos].startswith('        uses:'):
                    self.assertGreater(pos, 0)
                    self.assertTrue(lines[pos - 1].startswith('      - name: '), 'Named SDK setup is missing its step heading')
                else:
                    self.assertTrue(lines[pos].startswith('      - uses: '), 'SDK setup must be a real YAML step')
                self.assertEqual(lines[pos + 1].strip(), 'with:')
                self.assertEqual(lines[pos + 2].strip(), "packages: 'platform-tools'")
                total += 1
        self.assertEqual(total, len(SDK_JOBS))
        self.assertEqual(self.workflow.count(f'uses: {ACTION}'), len(SDK_JOBS))
        self.assertEqual(sum(job.count(f'uses: {ACTION}') for job in self.jobs.values()), len(SDK_JOBS))

    def test_both_android_jobs_install_api36_and_run_gradle_in_actions(self):
        for name in ACTIONS:
            with self.subTest(job=name):
                job = self.jobs[name]
                self.assertIn("sdkmanager 'platforms;android-36' 'build-tools;36.0.0'", job)
                self.assertIn('gradle/actions/setup-gradle@v4', job)
                self.assertIn('java-version: \'21\'', job)
                self.assertIn('run: python3 scripts/ci/test_r05a_hf01_sdk_setup.py', job)
        self.assertIn('gradle --no-daemon --console=plain :app:testDebugUnitTest', self.jobs['android-companion-jvm'])
        self.assertIn('gradle --no-daemon --console=plain :app:assembleDebug', self.jobs['android-companion-debug'])

    def test_no_retired_sdk_tools_installs(self):
        for name in SDK_JOBS:
            with self.subTest(job=name):
                block = self.jobs[name]
                for packages in re.findall(r'(?m)^\s*packages:\s*[\'\"]?([^\n\'\"]+)', block):
                    self.assertNotIn('tools', packages.split(), 'Retired tools package requested')
                self.assertNotRegex(block, r'(?m)^\s*run:\s*sdkmanager\s+tools(?:\s|$)')

    def test_emulator_bootstrap_precedes_sdkmanager_and_requires_instrumentation(self):
        job = self.jobs.get(EMULATOR)
        self.assertIsNotNone(job, 'API36 emulator job is mandatory')
        setup = '      - name: Initialize Android SDK command-line tools for API36\n'
        sdk = '      - name: Install Android API36 build-tools 36.0.0\n'
        test = '      - name: Android API36 rootless instrumentation\n'
        self.assertEqual(job.count(setup), 1)
        self.assertEqual(job.count(sdk), 1)
        self.assertEqual(job.count(test), 1)
        self.assertLess(job.index(setup), job.index(sdk))
        self.assertLess(job.index(sdk), job.index(test))
        self.assertNotRegex(job[:job.index(setup)], r'(?m)^\s*(?:run:\s*)?sdkmanager\b')
        self.assertIn("sdkmanager 'build-tools;36.0.0'", job)
        self.assertIn('command -v sdkmanager', job)
        self.assertIn('test -x "$ANDROID_HOME/build-tools/36.0.0/apksigner"', job)
        self.assertIn('reactivecircus/android-emulator-runner@v2', job)
        self.assertIn('connectedDebugAndroidTest', job)
        self.assertIn('r05c_verify_instrumentation.py', job)
        self.assertIn('needs: [go-contract, android-companion-jvm]', job)
        self.assertIn('script: |\n            set -eu\n', job)
        self.assertNotIn('continue-on-error', job)
        self.assertNotIn('|| true', job)


if __name__ == '__main__':
    unittest.main(verbosity=2)
