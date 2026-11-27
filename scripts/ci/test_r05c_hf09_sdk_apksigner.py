#!/usr/bin/env python3
"""HF09: installed SDK 36.0.0 apksigner required for both APK publication lanes.

No network, root, Gradle, SDK or device commands are launched here.
"""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
WORKFLOW=ROOT/'.github/workflows/devtool-xbs-ci.yml'
SDK_SIGNER='"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify "$APK"'
OLD_SIGNER='apksigner verify "$APK"'
RUN_EXPR='${{ github.event.pull_request.head.sha || github.sha }}'

class Refused(RuntimeError):pass

def inspect(workflow):
    for token in ('  android-companion-api36:\n','  android-companion-debug:\n',
                  '          script: |\n','      - name: Install Android API36 build-tools 36.0.0\n'):
        if workflow.count(token)!=1:raise Refused('MISSING_OR_AMBIGUOUS_ANCHOR '+token.strip())
    api=workflow.split('  android-companion-api36:\n',1)[1].split('\n  android-companion-debug:',1)[0]
    debug=workflow.split('  android-companion-debug:\n',1)[1]
    script=api.split('          script: |\n',1)[1].split('\n      - name:',1)[0]
    if script.count(SDK_SIGNER)!=1 or debug.count(SDK_SIGNER)!=1:
        raise Refused('SDK_SIGNER_MISSING_FROM_EITHER_JOB')
    if workflow.count(SDK_SIGNER)!=2 or workflow.count(' && '+OLD_SIGNER):
        raise Refused('UNPINNED_SIGNER_OR_DUPLICATE')
    # In particular, a bare executable name can never replace SDK's pinned signer.
    if 'test -x "$ANDROID_HOME/build-tools/36.0.0/apksigner"' not in api:
        raise Refused('SDK_INSTALL_PREFLIGHT_MISSING')
    if "sdkmanager 'build-tools;36.0.0'" not in api:
        raise Refused('API36_TOOL_INSTALL_MISSING')
    if "sdkmanager 'platforms;android-36' 'build-tools;36.0.0'" not in debug:
        raise Refused('DEBUG_TOOL_INSTALL_MISSING')
    if '      - name: Go race detector\n        run: go test -race ./internal/config ./internal/control ./internal/daemon' not in workflow:
        raise Refused('LINUX_RACE_GATE_MISSING')
    if 'needs: [go-contract, android-companion-jvm]' not in api or 'connectedDebugAndroidTest' not in script:
        raise Refused('INSTRUMENTATION_NOT_MANDATORY')
    if 'r05c_verify_instrumentation.py' not in script or "test \"$HEAD_SHA\" = '"+RUN_EXPR+"'" not in script:
        raise Refused('CASE_RECEIPT_OR_SOURCE_PIN_MISSING')
    if ' && '+SDK_SIGNER+' && mkdir -p android-evidence' not in script:
        raise Refused('FAIL_CLOSED_SIGNER_ORDER_MISSING')
    if debug.index('test -s "$APK"')>debug.index(SDK_SIGNER):
        raise Refused('DEBUG_APK_SIZE_NOT_VALIDATED')
    if 'if-no-files-found: error' not in api or 'if-no-files-found: error' not in debug:
        raise Refused('ARTIFACT_NOT_FAIL_CLOSED')
    return api,debug

class SdkSignerVersionPin(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow=WORKFLOW.read_text(encoding='utf-8')
        cls.api,cls.debug=inspect(cls.workflow)

    def test_exact_sdk_36_apksigner_both_jobs(self):
        self.assertEqual(self.workflow.count(SDK_SIGNER),2)
        self.assertIn('"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify "$APK"',self.api)
        self.assertIn('"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify "$APK"',self.debug)

    def test_preinstalled_tool_and_emulator_cases_required(self):
        self.assertIn('test -x "$ANDROID_HOME/build-tools/36.0.0/apksigner"',self.api)
        self.assertIn('r05c_verify_instrumentation.py',self.api)
        self.assertIn('go test -race',self.workflow)

    def test_either_unpinned_path_refused(self):
        for idx in range(2):
            with self.subTest(idx=idx):
                broken=self.workflow.replace(SDK_SIGNER,OLD_SIGNER,1) if idx==0 else self.workflow[::-1].replace(SDK_SIGNER[::-1], OLD_SIGNER[::-1],1)[::-1]
                with self.assertRaises(Refused):inspect(broken)

    def test_mismatched_sdk_version_refused(self):
        with self.assertRaises(Refused):inspect(self.workflow.replace('build-tools/36.0.0/apksigner','build-tools/35.0.0/apksigner',1))

    def test_preflight_removal_refused(self):
        with self.assertRaises(Refused):inspect(self.workflow.replace('test -x "$ANDROID_HOME/build-tools/36.0.0/apksigner"','echo apksigner available',1))

    def test_sha_binding_removal_refused(self):
        with self.assertRaises(Refused):inspect(self.workflow.replace('test "$HEAD_SHA" =', 'echo "$HEAD_SHA" =',1))

    def test_hosted_race_weakened_refused(self):
        with self.assertRaises(Refused):inspect(self.workflow.replace('go test -race ./internal/config', 'go test ./internal/config',1))

    def test_rootless_emulator_skipped_refused(self):
        with self.assertRaises(Refused):inspect(self.workflow.replace('connectedDebugAndroidTest','testDebugUnitTest',1))

    def test_debug_artifact_fail_open_refused(self):
        debug=self.workflow.split('  android-companion-debug:\n',1)[1]
        broken=self.workflow.replace(debug,debug.replace('if-no-files-found: error','if-no-files-found: ignore'))
        with self.assertRaises(Refused):inspect(broken)

if __name__=='__main__':unittest.main(verbosity=2)
