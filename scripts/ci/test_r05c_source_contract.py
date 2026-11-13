#!/usr/bin/env python3
"""Hosted source contracts. Full API36 instrumented results are checked separately."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT/'android-app/app/src/main/java/io/github/mikeceead/xbscompanion'
TEST = ROOT/'android-app/app/src/androidTest/java/io/github/mikeceead/xbscompanion'
WORKFLOW = ROOT/'.github/workflows/devtool-xbs-ci.yml'

class R05cSourceContract(unittest.TestCase):
    def test_api36_lane_is_mandatory_on_prs(self):
        text=WORKFLOW.read_text()
        section=text.split('  android-companion-api36:',1)[1].split('\n  android-companion-debug:',1)[0]
        self.assertIn('runs-on: ubuntu-latest',section)
        self.assertIn('needs: [go-contract, android-companion-jvm]',section)
        self.assertIn('reactivecircus/android-emulator-runner@v2',section)
        self.assertIn('api-level: 36',section)
        self.assertIn('arch: x86_64',section)
        self.assertIn('target: google_apis',section)
        self.assertIn('connectedDebugAndroidTest',section)
        self.assertIn('r05c_verify_instrumentation.py',section)
        self.assertIn('HEAD_SHA=$(git rev-parse HEAD)', section)
        self.assertIn('test "$HEAD_SHA" =', section)
        self.assertIn('if-no-files-found: error',section)
        self.assertNotIn('continue-on-error',section)
        self.assertNotIn('|| true',section)
        self.assertNotIn('workflow_dispatch',section)
        self.assertNotIn(' su -c ',section)

    def test_emulator_only_build_switch(self):
        text=(ROOT/'android-app/app/build.gradle').read_text()
        self.assertIn("testBuildType (project.hasProperty('xbsR05cEmulator') ? 'debug' : 'release')",text)
        self.assertIn('targetSdk xbsReleaseTargetSdk',text)

    def test_tasker_untrusted_worker_exception_guards(self):
        for name in ('TaskerPluginReceiver.java','TaskerConditionReceiver.java'):
            text=(MAIN/name).read_text()
            self.assertIn('catch(RuntimeException malformed)',text)
            self.assertIn('pending.finish();',text)
            self.assertIn('malformed_input_rejected',text)
            self.assertNotIn('malformed.getMessage()',text)

    def test_replay_policy_and_persistent_cursor(self):
        text=(MAIN/'EventBridgeStore.java').read_text()
        self.assertIn('static synchronized boolean accept(',text)
        self.assertIn('R05cReplayCursorPolicy.mayCommit(',text)
        self.assertIn('.commit();',text)
        policy=(MAIN/'R05cReplayCursorPolicy.java').read_text()
        self.assertIn('verifiedSequence < previousSequence',policy)
        self.assertIn('previousDigest.equals(verifiedDigest)',policy)

    def test_root_service_duplicate_owner_and_private_export(self):
        source=(MAIN/'RootCommandService.java').read_text()
        self.assertIn('if (isDuplicateRunForTest(runId))',source)
        self.assertIn('ACTIVE.containsKey(runId)',source)
        manifest=(ROOT/'android-app/app/src/main/AndroidManifest.xml').read_text()
        self.assertIn('android:name=".RootCommandService"',manifest)
        self.assertIn('android:name=".TaskerPluginReceiver"',manifest)
        self.assertIn('android:name=".TaskerConditionReceiver"',manifest)

    def test_api36_test_classes_no_root_mutation_or_conditional_skipping(self):
        names=('R05cBridgeReplayInstrumentedTest','R05cTaskerBoundaryInstrumentedTest',
               'R05cLifecycleInstrumentedTest','R05cUiSmokeInstrumentedTest')
        for name in names:
            text=(TEST/(name+'.java')).read_text()
            self.assertIn('@RunWith(AndroidJUnit4.class)',text)
            self.assertIn('@Test',text)
            self.assertNotIn('Assume.',text)
            self.assertNotIn('"su"',text)
            self.assertNotIn('ksud module install',text)

    def test_no_unbounded_artifact_exposure(self):
        workflow=WORKFLOW.read_text()
        self.assertIn('android-evidence/SOURCE_SHA',workflow)
        self.assertIn('android-evidence/SHA256SUMS',workflow)
        self.assertNotIn('upload-artifact@v3',workflow)
        self.assertNotIn('logcat',workflow)
        self.assertEqual(workflow.count('ref: ${{ github.event.pull_request.head.sha || github.sha }}'), 4)
        self.assertNotIn('\"$GITHUB_SHA\" > android-evidence/SOURCE_SHA', workflow)

if __name__=='__main__':unittest.main(verbosity=2)
