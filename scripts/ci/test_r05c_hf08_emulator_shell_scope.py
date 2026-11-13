#!/usr/bin/env python3
"""HF08: prove emulator-runner executes its commands in independent /bin/sh -c invocations.

The post-test SHA-bound APK publication MUST run in a *single* shell invocation;
previous YAML assigned APK and HEAD_SHA in other invocations, then failed despite
16 successful API36 instrumentation tests. No Android SDK, Gradle or device needed.
"""
from __future__ import annotations
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
WORKFLOW=ROOT/'.github/workflows/devtool-xbs-ci.yml'
JOB_START='  android-companion-api36:\n'
JOB_END='\n  android-companion-debug:'
SCRIPT_START='          script: |\n'
TARGET_APK='android-app/app/build/outputs/apk/debug/app-debug.apk'
EVIDENCE='android-evidence/xbs-companion-r05c-api36-debug.apk'
HEAD_EXPRESSION='${{ github.event.pull_request.head.sha || github.sha }}'

class Refused(RuntimeError): pass

def inspect(workflow:str)->str:
    if workflow.count(JOB_START)!=1 or workflow.count(JOB_END)!=1:
        raise Refused('API36_JOB_BOUNDARY_AMBIGUOUS')
    job=workflow.split(JOB_START,1)[1].split(JOB_END,1)[0]
    if job.count(SCRIPT_START)!=1: raise Refused('EMULATOR_SCRIPT_AMBIGUOUS')
    script=job.split(SCRIPT_START,1)[1].split('\n      - name:',1)[0]
    lines=script.splitlines()
    if len(lines)!=5 or lines[0]!='            set -eu':
        raise Refused('EMULATOR_COMMAND_INVENTORY_DRIFT')
    if 'connectedDebugAndroidTest' not in lines[1] or lines[1].count('gradle -p android-app')!=1:
        raise Refused('INSTRUMENTATION_NOT_REQUIRED')
    if lines[2].strip()!='python3 scripts/ci/r05c_verify_instrumentation.py --results android-app/app/build/outputs/androidTest-results/connected':
        raise Refused('SIXTEEN_CASE_GATE_NOT_REQUIRED')
    if lines[3].strip()!='gradle -p android-app --no-daemon --console=plain :app:assembleDebug':
        raise Refused('DEBUG_ASSEMBLY_NOT_REQUIRED')
    package=lines[4].strip()
    parts=package.split(' && ')
    if len(parts)!=8: raise Refused('PUBLICATION_NOT_SINGLE_FAIL_CLOSED_SHELL')
    tokens=[
        'APK='+TARGET_APK+'; HEAD_SHA=$(git rev-parse HEAD); test "$HEAD_SHA" = \'%s\''%HEAD_EXPRESSION,
        'test -s "$APK"',
        'apksigner verify "$APK"',
        'mkdir -p android-evidence',
        'cp "$APK" '+EVIDENCE,
        '(cd android-evidence && sha256sum xbs-companion-r05c-api36-debug.apk > SHA256SUMS)',
        'printf \'%s\\n\' "$HEAD_SHA" > android-evidence/SOURCE_SHA',
    ]
    # First six checks are joined with '&&'; nested checksum is its own group.
    # Split handles the nested `&&` in the checksum subshell, so 8 parts expected.
    if parts[:5]!=tokens[:5] or parts[5]!='(cd android-evidence' or parts[6]!='sha256sum xbs-companion-r05c-api36-debug.apk > SHA256SUMS)' or parts[7]!=tokens[6]:
        raise Refused('PUBLICATION_STEPS_WEAKENED_OR_REORDERED')
    for forbidden in ('|| true','continue-on-error','GITHUB_SHA','set +e'):
        if forbidden in script:raise Refused('EMULATOR_SCRIPT_FAIL_OPEN '+forbidden)
    if 'needs: [go-contract, android-companion-jvm]' not in job or 'if-no-files-found: error' not in job:
        raise Refused('HOSTED_JOB_GATE_WEAKENED')
    if '      - name: Go race detector\n        run: go test -race ./internal/config ./internal/control ./internal/daemon' not in workflow:
        raise Refused('LINUX_RACE_GATE_WEAKENED')
    return package

class EmulatorShellScope(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow=WORKFLOW.read_text(encoding='utf-8')
        cls.package=inspect(cls.workflow)

    def test_exact_sha_bound_publication_is_one_shell_invocation(self):
        self.assertIn('APK='+TARGET_APK+'; HEAD_SHA=$(git rev-parse HEAD)',self.package)
        self.assertIn('test "$HEAD_SHA" =',self.package)
        self.assertIn('test -s "$APK"',self.package)
        self.assertIn('apksigner verify "$APK"',self.package)
        self.assertIn('SHA256SUMS',self.package)
        self.assertIn('SOURCE_SHA',self.package)

    def test_all_sixteen_emulator_cases_and_linux_race_remain_required(self):
        self.assertIn('r05c_verify_instrumentation.py',self.workflow)
        self.assertIn('go test -race ./internal/config ./internal/control ./internal/daemon',self.workflow)

    def test_legacy_independent_variable_assignments_are_rejected(self):
        mutated=self.workflow.replace('            APK='+TARGET_APK+'; HEAD_SHA=', '            APK='+TARGET_APK+'\n            HEAD_SHA=',1)
        with self.assertRaises(Refused):inspect(mutated)

    def test_omitted_apk_validation_is_rejected(self):
        with self.assertRaises(Refused):inspect(self.workflow.replace(' && test -s "$APK"','',1))

    def test_skipped_instrumentation_receipt_is_rejected(self):
        with self.assertRaises(Refused):inspect(self.workflow.replace('            python3 scripts/ci/r05c_verify_instrumentation.py --results', '            : # disabled\n            python3 scripts/ci/r05c_verify_instrumentation.py --results',1))

    def test_loss_of_head_sha_binding_is_rejected(self):
        with self.assertRaises(Refused):inspect(self.workflow.replace("test \"$HEAD_SHA\" = '" + HEAD_EXPRESSION + "'", "test \"$HEAD_SHA\" = '" + "0" * 40 + "'", 1))

    def test_chained_exit_checks_cannot_be_silenced(self):
        with self.assertRaises(Refused):inspect(self.workflow.replace(' && apksigner verify "$APK"','; apksigner verify "$APK"',1))

    def test_hosted_linux_race_is_immutable(self):
        with self.assertRaises(Refused):inspect(self.workflow.replace('go test -race ./internal/config', 'go test ./internal/config',1))

    def _execute(self,*,exists=True,bad_sig=False,wrong_sha=False):
        shell=shutil.which('sh')
        if not shell: self.fail('POSIX shell unavailable')
        with tempfile.TemporaryDirectory(prefix='xbs-r05c-hf08-') as tmp:
            root=Path(tmp)
            subprocess.run(['git','init','-q',str(root)],check=True,stdout=subprocess.DEVNULL)
            subprocess.run(['git','-C',str(root),'config','user.name','XBS HF08 fixture'],check=True)
            subprocess.run(['git','-C',str(root),'config','user.email','xbs-hf08@example.invalid'],check=True)
            (root/'tracked.txt').write_text('fixture\n')
            subprocess.run(['git','-C',str(root),'add','tracked.txt'],check=True)
            subprocess.run(['git','-C',str(root),'commit','-qm','fixture'],check=True)
            head=subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True).strip()
            apk=root/TARGET_APK
            if exists:
                apk.parent.mkdir(parents=True)
                apk.write_bytes(b'PK\\x03\\x04fake-fixture-apk')
            bin_dir=root/'bin';bin_dir.mkdir()
            signer=bin_dir/'apksigner'
            signer.write_text('#!'+shell+'\n[ "$1" = "verify" ] && [ -s "$2" ] && [ "${XBS_FAKE_SIGNATURE_FAILURE:-0}" != 1 ]\n')
            signer.chmod(0o755)
            env={**os.environ,'PATH':str(bin_dir)+os.pathsep+os.environ.get('PATH',''), 'XBS_FAKE_SIGNATURE_FAILURE':'1' if bad_sig else '0'}
            expected='0'*40 if wrong_sha else head
            command=self.package.replace(HEAD_EXPRESSION,expected)
            run=subprocess.run([shell,'-c',command],cwd=root,env=env,capture_output=True,text=True)
            dest=root/EVIDENCE
            source=root/'android-evidence/SOURCE_SHA'
            checksum=root/'android-evidence/SHA256SUMS'
            if exists and not bad_sig and not wrong_sha:
                self.assertEqual(run.returncode,0,run.stderr)
                self.assertEqual(dest.read_bytes(),apk.read_bytes())
                self.assertEqual(source.read_text(),head+'\n')
                self.assertEqual(checksum.read_text(),hashlib.sha256(apk.read_bytes()).hexdigest()+'  '+dest.name+'\n')
            else:
                self.assertNotEqual(run.returncode,0,'invalid artifact/signature/SHA was accepted')
                self.assertFalse(source.exists(),'unqualified artifact gained a SHA receipt')

    def test_actual_separate_shell_success_and_sha_receipt(self):self._execute()
    def test_actual_shell_missing_apk_fails_closed(self):self._execute(exists=False)
    def test_actual_shell_bad_apk_signature_fails_closed(self):self._execute(bad_sig=True)
    def test_actual_shell_wrong_git_sha_fails_closed(self):self._execute(wrong_sha=True)

if __name__=='__main__':unittest.main(verbosity=2)
