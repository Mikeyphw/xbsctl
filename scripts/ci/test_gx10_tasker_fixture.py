#!/usr/bin/env python3
"""Negative controls for actual hosted fixture evidence, never synthetic Android acceptance."""
from pathlib import Path
import os
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace
from gx03_evidence import Refused, PKG
import gx10_tasker_fixture as fixture

ROOT=Path(__file__).resolve().parents[2]

class FixtureEvidenceTests(unittest.TestCase):
    def test_bounds_and_package_binding(self):
        raw=f'<hierarchy><node package="{PKG}" text="Save state" enabled="true" bounds="[100,100][200,200]" /></hierarchy>'.encode()
        nodes=fixture.parse_window(raw)
        self.assertEqual(fixture.visible_center(nodes,PKG,'Save state'),(150,150))
        self.assertIsNone(fixture.visible_center(nodes,fixture.TRUSTED,'Save state'))
        for attr,value in [('bounds','[0,0][5000,50]'),('bounds','[50,50][10,10]')]:
            nodes[0].set(attr,value)
            with self.assertRaises(Refused):fixture.visible_center(nodes,PKG,'Save state')
        nodes[0].set('bounds','[100,100][200,200]')
        with self.assertRaises(Refused):fixture.visible_center(nodes*2,PKG,'Save state')
    def test_xml_refusals(self):
        for blob in (b'',b'<hierarchy',b'<other/>',b'<!DOCTYPE hierarchy><hierarchy/>',b'x'*512001):
            with self.subTest(blob=blob[:40]),self.assertRaises(Refused):fixture.parse_window(blob)
    def test_exact_result_and_foreign_window(self):
        for mode,(_,codes) in fixture.MODES.items():
            for code in codes:
                raw=f'<hierarchy><node package="{fixture.TRUSTED}" text="XBS_FIXTURE_PASS_{mode.upper()}_{code}" /></hierarchy>'.encode()
                nodes=fixture.parse_window(raw)
                self.assertEqual(fixture.fixture_result(nodes,fixture.TRUSTED,mode)['positive_ordered_result'],code)
                self.assertIsNone(fixture.fixture_result(nodes,fixture.UNTRUSTED,mode))
                with self.assertRaises(Refused):fixture.fixture_result(nodes*2,fixture.TRUSTED,mode)
            for status in ('XBS_FIXTURE_PASS_OTHER_16','XBS_FIXTURE_PASS_'+mode.upper()+'_18','XBS_FIXTURE_FAIL_sender'):
                nodes=fixture.parse_window(f'<hierarchy><node package="{fixture.TRUSTED}" text="{status}" /></hierarchy>'.encode())
                with self.assertRaises(Refused):fixture.fixture_result(nodes,fixture.TRUSTED,mode)
        self.assertIsNone(fixture.fixture_result([],fixture.TRUSTED,'state'))
    def test_untrusted_editor_marker(self):
        nodes=fixture.parse_window(f'<hierarchy><node package="{fixture.UNTRUSTED}" text="XBS_FIXTURE_PASS_UNTRUSTED_EDITOR" /></hierarchy>'.encode())
        self.assertEqual(fixture.fixture_result(nodes,fixture.UNTRUSTED,'untrusted_editor'),{'untrusted_editor_refused':True})
    def test_apk_identity_and_signer(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'test.apk';path.write_bytes(b'x'*2000)
            def run(argv):
                return SimpleNamespace(stdout="package: name='"+fixture.TRUSTED+"'" if argv[0]=='aapt' else 'Signer #1 certificate SHA-256 digest: '+'a'*64)
            self.assertEqual(fixture.verify_apk(path,fixture.TRUSTED,'sign','aapt',run)['certificate_sha256'],'a'*64)
            with self.assertRaises(Refused):fixture.verify_apk(path,fixture.UNTRUSTED,'sign','aapt',run)
            with self.assertRaises(Refused):fixture.verify_apk(path,fixture.TRUSTED,'sign','aapt',lambda args:SimpleNamespace(stdout=''))
            path.unlink()
            with self.assertRaises(Refused):fixture.verify_apk(path,fixture.TRUSTED,'sign','aapt',run)
    def test_nonhosted_refuses_before_subprocess(self):
        def never(*args,**kw):self.fail('unsafe host must not execute')
        identity={'api_level':36,'serial':'emulator-5554','source_sha':'a'*40}
        with patch.dict(os.environ,{},clear=True),self.assertRaises(Refused):fixture.collect(ROOT,'emulator-5554',identity,never,never,None,None)
    def test_preexisting_package_refuses_before_build(self):
        identity={'api_level':36,'serial':'emulator-5554','source_sha':'a'*40}
        def adb(serial,*args,**kw):
            value={'ro.kernel.qemu':'1','ro.build.version.sdk':'36','-u':'2000'}.get(args[-1],'package:/existing.apk')
            return SimpleNamespace(stdout=value)
        env={'GITHUB_ACTIONS':'true','RUNNER_ENVIRONMENT':'github-hosted','XBS_EXPECTED_HEAD_SHA':'a'*40}
        with patch.dict(os.environ,env,clear=True),patch.object(fixture,'build_fixtures') as build,self.assertRaises(Refused):
            fixture.collect(ROOT,'emulator-5554',identity,adb,None,None,None)
        build.assert_not_called()
    def test_real_routes_remain_connected(self):
        java=(ROOT/'android-app/tasker-host-fixture/src/main/java/io/github/mikeceead/xbsfixture/HostActivity.java').read_text()
        for s in ('startActivityForResult','getApkContentsSigners','setShareIdentityEnabled','sendOrderedBroadcast','signed_replay_not_refused','tampered_signature_not_refused','hidden_sender_not_refused','untrusted_editor_signed'):
            self.assertIn(s,java)
        host=(ROOT/'scripts/ci/gx03_emulator_host.py').read_text()
        self.assertIn("report['gx10_tasker_host']=collect_tasker",host)
        self.assertIn("wait_delta('listening_PAUSE')",host)
        self.assertIn("wait_delta('stopped_PAUSE')",host)
        process=(ROOT/'android-app/app/src/androidTest/java/io/github/mikeceead/xbscompanion/Gx03ProcessDeathInstrumentedTest.java').read_text()
        for s in ('assertNotEquals("Process was not externally killed','XbsTileDecision.Phase.PENDING','XbsTileDecision.Phase.RECOVERY_REQUIRED','XbsTileDecision.Phase.FAILED','XbsTileController.requestToggle','EventBridgeStore.save'):
            self.assertIn(s,process)
        self.assertIn('python3 -B scripts/ci/test_gx10_tasker_fixture.py',(ROOT/'.github/workflows/devtool-xbs-ci.yml').read_text())

if __name__=='__main__':unittest.main()
