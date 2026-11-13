#!/usr/bin/env python3
"""R05C HF03/HF01: execute the real R05A guard against correctly shaped YAML.

The emulator uses a *named* SDK step (uses: at 8 spaces, no leading '-').
The original HF03 fixture incorrectly modelled it as '- uses:' and missed the
real local precommit failure. All mutations below operate on the named form.
"""
from __future__ import annotations
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / '.github/workflows/devtool-xbs-ci.yml'
GUARD = ROOT / 'scripts/ci/test_r05a_hf01_sdk_setup.py'
ACTION = 'android-actions/setup-android@v3'
NAMED_STEP = ("      - name: Initialize Android SDK command-line tools for API36\n"
              "        uses: android-actions/setup-android@v3\n"
              "        with:\n"
              "          packages: 'platform-tools'\n")
UNNAMED_STEP = ("      - uses: android-actions/setup-android@v3\n"
                "        with:\n"
                "          packages: 'platform-tools'\n")


def load_guard():
    spec = importlib.util.spec_from_file_location('xbs_r05c_hf03_hf01_real_guard', GUARD)
    if spec is None or spec.loader is None:
        raise AssertionError('Cannot load real SDK guard')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def result_for(workflow):
    module = load_guard()
    with tempfile.TemporaryDirectory(prefix='xbs-r05c-hf03-hf01-') as tmp:
        path = Path(tmp) / 'workflow.yml'
        path.write_text(workflow, encoding='utf-8')
        module.WORKFLOW = path
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(module.AndroidSdkBootstrapTest)
        result = unittest.TestResult()
        suite.run(result)
        return result


def sections(text):
    begin = '  android-companion-api36:\n'
    end = '  android-companion-debug:\n'
    if text.count(begin) != 1 or text.count(end) != 1:
        raise AssertionError('Unexpected Android job order/inventory')
    a = text.index(begin)
    b = text.index(end, a)
    return text[:a], text[a:b], text[b:]


class R05cHf03NamedSdkStepRegression(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = WORKFLOW.read_text(encoding='utf-8')
        cls.prefix, cls.job, cls.suffix = sections(cls.workflow)

    def assert_rejected(self, mutant):
        self.assertNotEqual(mutant, self.workflow, 'mutant is identical to baseline')
        result = result_for(mutant)
        self.assertEqual(result.testsRun, 4, 'guard did not execute all tests')
        self.assertFalse(result.wasSuccessful(), 'unsafe workflow passed the actual guard')
        self.assertGreater(len(result.failures) + len(result.errors), 0)

    def test_positive_real_named_step_three_job_contract(self):
        self.assertEqual(self.job.count(NAMED_STEP), 1)
        self.assertNotIn('      - uses: android-actions/setup-android@v3\n', self.job)
        result = result_for(self.workflow)
        self.assertEqual(result.testsRun, 4)
        self.assertTrue(result.wasSuccessful(), str(result.failures + result.errors))

    def test_missing_named_sdk_step_rejected(self):
        self.assert_rejected(self.prefix + self.job.replace(NAMED_STEP, '', 1) + self.suffix)

    def test_duplicate_named_sdk_step_rejected(self):
        self.assert_rejected(self.prefix + self.job.replace(NAMED_STEP, NAMED_STEP + NAMED_STEP, 1) + self.suffix)

    def test_unsupported_legacy_tools_package_rejected(self):
        self.assert_rejected(self.prefix + self.job.replace("packages: 'platform-tools'", "packages: 'tools'", 1) + self.suffix)

    def test_wrong_platform_package_rejected(self):
        self.assert_rejected(self.prefix + self.job.replace("packages: 'platform-tools'", "packages: 'build-tools;36.0.0'", 1) + self.suffix)

    def test_named_step_without_with_rejected(self):
        self.assert_rejected(self.prefix + self.job.replace("        with:\n          packages: 'platform-tools'\n", '', 1) + self.suffix)

    def test_sdk_manager_reordered_before_setup_rejected(self):
        start = self.job.index(NAMED_STEP)
        end = self.job.index('      - name: Install Android API36 build-tools 36.0.0\n', start)
        next_step = self.job.index('      - name: Android API36 rootless instrumentation\n', end)
        bootstrap = self.job[start:end]
        install = self.job[end:next_step]
        mutant = self.job[:start] + install + bootstrap + self.job[next_step:]
        self.assert_rejected(self.prefix + mutant + self.suffix)

    def test_stray_sdk_action_elsewhere_rejected(self):
        extra = ('  android-extra-unaudited:\n    runs-on: ubuntu-latest\n    steps:\n' + UNNAMED_STEP)
        self.assert_rejected(self.workflow.replace('  android-companion-debug:\n', extra + '  android-companion-debug:\n', 1))

    def test_missing_jvm_sdk_action_rejected(self):
        self.assert_rejected(self.workflow.replace(UNNAMED_STEP, '', 1))

    def test_missing_debug_sdk_action_rejected(self):
        self.assert_rejected(self.prefix + self.job + self.suffix.replace(UNNAMED_STEP, '', 1))

    def test_badly_indented_named_uses_rejected(self):
        self.assert_rejected(self.prefix + self.job.replace(
            "        uses: android-actions/setup-android@v3\n",
            "      uses: android-actions/setup-android@v3\n", 1) + self.suffix)

    def test_guard_counts_named_and_unnamed_use_sites(self):
        source = GUARD.read_text(encoding='utf-8')
        self.assertIn('SDK_JOBS', source)
        self.assertIn('re.fullmatch(', source)
        self.assertIn('re.escape(ACTION)', source)
        self.assertIn('sum(job.count(', source)
        self.assertNotIn("line.strip() == f'- uses: {ACTION}'", source)


if __name__ == '__main__':
    unittest.main(verbosity=2)
