#!/usr/bin/env python3
"""R05A HF01: fail CI before SDK bootstrap when retired `tools` is requested.

Uses only the Python standard library; remains runnable without Android SDK or Gradle.
"""
from pathlib import Path
import re
import unittest

WORKFLOW = Path(__file__).resolve().parents[2] / '.github/workflows/devtool-xbs-ci.yml'
ACTIONS = ('android-companion-jvm', 'android-companion-debug')
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
        for name in ACTIONS:
            with self.subTest(job=name):
                job = self.jobs.get(name)
                self.assertIsNotNone(job, f'Missing Android job: {name}')
                lines = job.splitlines()
                positions = [i for i, line in enumerate(lines) if line.strip() == f'- uses: {ACTION}']
                self.assertEqual(len(positions), 1, f'Expected one SDK setup in {name}')
                pos = positions[0]
                self.assertEqual(lines[pos + 1].strip(), 'with:')
                self.assertEqual(lines[pos + 2].strip(), "packages: 'platform-tools'")
                total += 1
        self.assertEqual(total, 2)
        self.assertEqual(self.workflow.count(f'uses: {ACTION}'), 2)

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
        for name in ACTIONS:
            with self.subTest(job=name):
                block = self.jobs[name]
                for packages in re.findall(r'(?m)^\s*packages:\s*[\'\"]?([^\n\'\"]+)', block):
                    self.assertNotIn('tools', packages.split(), 'Retired tools package requested')
                self.assertNotRegex(block, r'(?m)^\s*run:\s*sdkmanager\s+tools(?:\s|$)')


if __name__ == '__main__':
    unittest.main(verbosity=2)
