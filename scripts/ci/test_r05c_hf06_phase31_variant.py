#!/usr/bin/env python3
"""R05C HF06: phase31 release-graph task registration is isolated from debug emulator builds.

No Gradle invocation: source contract is executed by the real repository's all-Python suite.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'android-app/gradle/phase31-release-seal.gradle'
APP = ROOT / 'android-app/app/build.gradle'
WORKFLOW = ROOT / '.github/workflows/devtool-xbs-ci.yml'
PREFIX = "if (!project.hasProperty('xbsR05cEmulator')) {\n"
START = 'gradle.projectsEvaluated {\n'
END = "    (phase31GraphSnapshot['assembleReleaseOrder'] as Set).add(':app:generateReleaseBaselineProfile')\n}\n}"


def validate(source, app, workflow):
    if source.count(PREFIX + START) != 1:
        raise AssertionError('Release-only task ordering must be conditionally registered exactly once')
    if source.count(START) != 1 or source.count(END) != 1:
        raise AssertionError('Phase31 release-only block or closing guard is missing')
    left = source.index(PREFIX + START)
    end = source.index(END, left) + len(END)
    ordering = source[left:end]
    task = "project(':app').tasks.named('testReleaseUnitTest')"
    if source.count(task) != 1 or task not in ordering:
        raise AssertionError('Release unit test task must be ordered ONLY in guarded block')
    required = (
        "project(':app').tasks.named('lintRelease')",
        "project(':app').tasks.named('assembleRelease')",
        "project(':app').tasks.named('connectedReleaseAndroidTest')",
        "project(':benchmark').tasks.named('connectedCheck')",
        "project(':app').tasks.named('generateReleaseBaselineProfile')",
        "project(':phase31-testkit').tasks.named('test')",
        "tasks.named('phase31PythonContracts')",
        "provider.configure { mustRunAfter cleanTask }",
    )
    for item in required:
        if item not in ordering:
            raise AssertionError('Missing release-only requirement: ' + item)
    if 'ext.phase31CandidateGatePaths' not in source or \
            "dependsOn ':app:testReleaseUnitTest', ':app:lintRelease', ':app:assembleRelease'," not in source:
        raise AssertionError('Production release candidate gates weakened')
    if "testBuildType (project.hasProperty('xbsR05cEmulator') ? 'debug' : 'release')" not in app:
        raise AssertionError('Debug emulator switch must preserve release as default')
    if "-PxbsR05cEmulator=true" not in workflow or \
            ':app:connectedDebugAndroidTest' not in workflow or \
            'r05c_verify_instrumentation.py' not in workflow:
        raise AssertionError('Real API36 debug instrumentation acceptance not retained')
    if 'needs: [go-contract, android-companion-jvm]' not in workflow or \
            'reactivecircus/android-emulator-runner@v2' not in workflow:
        raise AssertionError('API36 test gate is no longer mandatory')
    if 'continue-on-error: true' in workflow or 'go test -race ./internal/config ./internal/control ./internal/daemon' not in workflow:
        raise AssertionError('Hosted race/CI gate weakened')
    return True


class Phase31ReleaseGraphIsolation(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SCRIPT.read_text(encoding='utf-8')
        cls.app = APP.read_text(encoding='utf-8')
        cls.workflow = WORKFLOW.read_text(encoding='utf-8')

    def test_release_gate_remains_strict_and_emulator_is_isolated(self):
        self.assertTrue(validate(self.source, self.app, self.workflow))

    def test_guard_must_wrap_registered_task_providers(self):
        source = self.source.replace(PREFIX + START, START, 1)
        with self.assertRaisesRegex(AssertionError, 'conditionally registered'):
            validate(source, self.app, self.workflow)

    def test_unguarded_extra_release_lookup_is_rejected(self):
        source = self.source + "\nproject(':app').tasks.named('testReleaseUnitTest')\n"
        with self.assertRaisesRegex(AssertionError, 'ONLY in guarded block'):
            validate(source, self.app, self.workflow)

    def test_missing_release_candidate_dependencies_rejected(self):
        source = self.source.replace("dependsOn ':app:testReleaseUnitTest', ':app:lintRelease', ':app:assembleRelease',", 'dependsOn \' :app:assembleRelease\'', 1)
        with self.assertRaisesRegex(AssertionError, 'candidate gates weakened'):
            validate(source, self.app, self.workflow)

    def test_missing_release_task_ordering_rejected(self):
        source = self.source.replace("project(':app').tasks.named('connectedReleaseAndroidTest')", "project(':app').tasks.named('connectedDebugAndroidTest')", 1)
        with self.assertRaisesRegex(AssertionError, 'release-only requirement'):
            validate(source, self.app, self.workflow)

    def test_release_build_default_cannot_be_reversed(self):
        app = self.app.replace("? 'debug' : 'release'", "? 'release' : 'debug'", 1)
        with self.assertRaisesRegex(AssertionError, 'release as default'):
            validate(self.source, app, self.workflow)

    def test_api36_emulator_cannot_be_weakened(self):
        workflow = self.workflow.replace(':app:connectedDebugAndroidTest', ':app:assembleDebug', 1)
        with self.assertRaisesRegex(AssertionError, 'instrumentation acceptance'):
            validate(self.source, self.app, workflow)

    def test_hosted_race_cannot_be_omitted(self):
        workflow = self.workflow.replace('go test -race ./internal/config ./internal/control ./internal/daemon', 'go test ./internal/config ./internal/control ./internal/daemon', 1)
        with self.assertRaisesRegex(AssertionError, 'race/CI gate weakened'):
            validate(self.source, self.app, workflow)


if __name__ == '__main__':
    unittest.main(verbosity=2)
