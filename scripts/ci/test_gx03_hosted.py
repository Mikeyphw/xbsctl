#!/usr/bin/env python3
"""GX03 independent negative/mutation matrix; stdlib, no Android or Git needed."""
from __future__ import annotations
import io
import sys
from pathlib import Path
import struct
import tempfile
import types
import unittest
import zlib
sys.path.insert(0,str(Path(__file__).parent))
import gx03_evidence as e
import gx03_emulator_host as host


def png(width=1080,height=1200,*,mode=2,filters=0,extra=False):
    bpp=3 if mode==2 else 4
    ihdr=struct.pack('>IIBBBBB',width,height,8,mode,0,0,0)
    data=b''.join(b'\0'+bytes([42,11,17,255][:bpp])*width for _ in range(height))
    return e.PNG+e.chunk(b'IHDR',ihdr)+(e.chunk(b'tEXt',b'private-marker') if extra else b'')+e.chunk(b'IDAT',zlib.compress(data))+e.chunk(b'IEND',b'')


def fixture_xml(cases,duplicate=False):
    names=[]
    for cl,method,status in cases:
        extra=('<'+status+'/>' if status else '')
        names.append('<testcase classname="io.github.mikeceead.xbscompanion.'+cl+'" name="'+method+'">'+extra+'</testcase>')
    if duplicate:names.append(names[0])
    return '<testsuite tests="'+str(len(names))+'">'+''.join(names)+'</testsuite>'

class GX03EvidenceTests(unittest.TestCase):
    def assertRefused(self,fn,*args,**kw):
        with self.assertRaises(e.Refused):fn(*args,**kw)

    def cases(self,mapping=None,**kw):
        with tempfile.TemporaryDirectory() as t:
            folder=Path(t)
            cases=[(cl,m,kw.get('failure')) for cl,methods in (mapping or e.JOURNEYS).items() for m in sorted(methods)]
            (folder/'results.xml').write_text(fixture_xml(cases,duplicate=kw.get('duplicate',False)))
            if kw.get('missing'): (folder/'results.xml').write_text(fixture_xml(cases[:-1]))
            return e.junit_receipt(folder,mapping or e.JOURNEYS)

    def test_01_gx03_named_real_journey_methods(self):
        r=self.cases();self.assertEqual(r['required_testcases'],3);self.assertEqual(r['skipped_cases'],0)
    def test_02_missing_method_refuses(self):self.assertRefused(self.cases,missing=True)
    def test_03_skip_refuses(self):self.assertRefused(self.cases,failure='skipped')
    def test_04_failure_refuses(self):self.assertRefused(self.cases,failure='failure')
    def test_05_error_refuses(self):self.assertRefused(self.cases,failure='error')
    def test_06_duplicate_refuses(self):self.assertRefused(self.cases,duplicate=True)
    def test_07_zero_results_refuses(self):
        with tempfile.TemporaryDirectory() as t:self.assertRefused(e.junit_receipt,Path(t),e.JOURNEYS)
    def test_08_stageA_real_test_required(self):self.assertEqual(self.cases(e.STAGE_A)['required_testcases'],1)
    def test_09_stageB_real_test_required(self):self.assertEqual(self.cases(e.STAGE_B)['required_testcases'],1)
    def test_10_api26_requires_four_real_cases(self):self.assertEqual(self.cases(e.COMPAT[26])['required_testcases'],4)
    def test_11_api34_requires_five_real_cases(self):self.assertEqual(self.cases(e.COMPAT[34])['required_testcases'],5)
    def test_12_wrong_compat_shard_refused(self):self.assertRefused(self.cases,e.COMPAT[34],missing=True)
    def test_13_non_emulator_serial_refused(self):self.assertRefused(e.validate_identity,'a'*40,'8','1',36,'localhost:5555','1','arm64-v8a')
    def test_14_wrong_api_refused(self):self.assertRefused(e.validate_identity,'a'*40,'8','1',35,'emulator-5554','1','x86_64')
    def test_15_qemu_not_one_refused(self):self.assertRefused(e.validate_identity,'a'*40,'8','1',36,'emulator-5554','0','x86_64')
    def test_16_run_id_bad_refused(self):self.assertRefused(e.validate_identity,'a'*40,'8x','1',36,'emulator-5554','1','x86_64')
    def test_17_attempt_bad_refused(self):self.assertRefused(e.validate_identity,'a'*40,'8','0',36,'emulator-5554','1','x86_64')
    def test_18_sha_bad_refused(self):self.assertRefused(e.validate_identity,'1','8','1',36,'emulator-5554','1','x86_64')
    def test_19_valid_identity_no_false_root_claim(self):
        r=e.validate_identity('a'*40,'8','1',36,'emulator-5554','1','x86_64')
        self.assertFalse(r['physical_root_qualified']);self.assertFalse(r['signed_release'])
    def test_20_original_case_ids_preserved(self):self.assertEqual(len(e.ORIGINAL_CASES),21)
    def test_21_png_valid_and_band_redaction(self):
        raw=png(extra=True);red,meta=e.redact_png(raw,1080)
        self.assertIn(b'IDAT',red);self.assertNotIn(b'private-marker',red)
        self.assertEqual(meta['width_dp'],360);self.assertEqual(meta['masked_top_px'],100)
    def test_22_png_wrong_width_refused(self):self.assertRefused(e.redact_png,png(),1179)
    def test_23_png_too_short_refused(self):self.assertRefused(e.redact_png,png(height=200),1080)
    def test_24_png_invalid_signature_refused(self):self.assertRefused(e.redact_png,b'bad',1080)
    def test_25_png_bad_crc_refused(self):
        raw=bytearray(png());raw[32]^=1;self.assertRefused(e.redact_png,bytes(raw),1080)
    def test_26_png_reject_indexed_color(self):self.assertRefused(e.redact_png,png(mode=3),1080)
    def test_27_png_trailing_bytes_refused(self):self.assertRefused(e.redact_png,png()+b'x',1080)
    def test_28_png_no_data_refused(self):self.assertRefused(e.redact_png,e.PNG+e.chunk(b'IEND',b''),1080)
    def test_29_png_no_private_metadata_preserved(self):
        red,meta=e.redact_png(png(extra=True),1080)
        self.assertTrue(meta['ancillary_png_metadata_removed'])
    def test_30_png_hash_changes_after_mask(self):
        red,meta=e.redact_png(png(),1080)
        self.assertNotEqual(meta['raw_sha256_local_only'],meta['redacted_sha256'])
    def test_31_apk_missing_refused(self):
        with tempfile.TemporaryDirectory() as t:
            paths={'app':Path(t)/'a.apk','test':Path(t)/'b.apk'}
            self.assertRefused(e.artifact_apk_pair,paths,'apksigner','aapt',lambda a:None)
    def test_32_apk_bad_signature_refused(self):
        with tempfile.TemporaryDirectory() as t:
            paths={'app':Path(t)/'a.apk','test':Path(t)/'b.apk'}
            for path in paths.values():path.write_bytes(b'A'*12000)
            proc=lambda argv:types.SimpleNamespace(returncode=1,stdout='')
            self.assertRefused(e.artifact_apk_pair,paths,'apksigner','aapt',proc)
    def test_33_apk_package_mismatch_refused(self):
        with tempfile.TemporaryDirectory() as t:
            paths={'app':Path(t)/'a.apk','test':Path(t)/'b.apk'}
            for path in paths.values():path.write_bytes(b'A'*12000)
            def proc(argv):
                s='Signer #1 certificate SHA-256 digest: '+'a'*64 if 'apksigner'==argv[0] else "package: name='wrong.pkg'"
                return types.SimpleNamespace(returncode=0,stdout=s)
            self.assertRefused(e.artifact_apk_pair,paths,'apksigner','aapt',proc)
    def test_34_apk_same_signer_enforced(self):
        with tempfile.TemporaryDirectory() as t:
            paths={'app':Path(t)/'a.apk','test':Path(t)/'b.apk'}
            paths['app'].write_bytes(b'A'*12000);paths['test'].write_bytes(b'B'*12000)
            def proc(argv):
                if argv[0]=='apksigner':
                    mark='a' if argv[-1].endswith('a.apk') else 'b'
                    return types.SimpleNamespace(returncode=0,stdout='Signer #1 certificate SHA-256 digest: '+mark*64)
                name=e.PKG+'.test' if argv[-1].endswith('b.apk') else e.PKG
                return types.SimpleNamespace(returncode=0,stdout="package: name='"+name+"'")
            self.assertRefused(e.artifact_apk_pair,paths,'apksigner','aapt',proc)
    def test_35_apk_valid_pair_real_hashes(self):
        with tempfile.TemporaryDirectory() as t:
            paths={'app':Path(t)/'a.apk','test':Path(t)/'b.apk'}
            paths['app'].write_bytes(b'A'*12000);paths['test'].write_bytes(b'B'*13000)
            def proc(argv):
                if argv[0]=='apksigner':return types.SimpleNamespace(returncode=0,stdout='Signer #1 certificate SHA-256 digest: '+'a'*64)
                return types.SimpleNamespace(returncode=0,stdout="package: name='"+(e.PKG+'.test' if argv[-1].endswith('b.apk') else e.PKG)+"'")
            r=e.artifact_apk_pair(paths,'apksigner','aapt',proc)
            self.assertNotEqual(r['app']['sha256'],r['test']['sha256'])
    def test_36_known_cases_no_automatic_pass(self):
        self.assertNotIn('EM-019',e.ORIGINAL_CASES)
    def test_37_expected_cases_in_matrix(self):
        a=[{'id':i,'phases':['R10']} for i in e.ORIGINAL_CASES]
        a +=[{'id':'OTHER-'+str(i),'phases':[]} for i in range(131-len(a))]
        inv=e.expected_inventory({'cases':a});self.assertEqual(inv['original_case_count'],21)
    def test_38_duplicate_original_matrix_refused(self):
        a=[{'id':e.ORIGINAL_CASES[0],'phases':['R10']}]*131
        self.assertRefused(e.expected_inventory,{'cases':a})


class GX03DirectProcessStageTests(unittest.TestCase):
    def sample(self,method='stageAWriteDurablePendingOwnership'):
        clazz='io.github.mikeceead.xbscompanion.Gx03ProcessDeathInstrumentedTest'
        selector=clazz+'#'+method
        raw=('INSTRUMENTATION_STATUS: class='+clazz+'\n'
             'INSTRUMENTATION_STATUS: numtests=1\n'
             'INSTRUMENTATION_STATUS: test='+method+'\n'
             'INSTRUMENTATION_STATUS_CODE: 1\n'
             'INSTRUMENTATION_STATUS: class='+clazz+'\n'
             'INSTRUMENTATION_STATUS: test='+method+'\n'
             'INSTRUMENTATION_STATUS_CODE: 0\n'
             'INSTRUMENTATION_RESULT: stream=\n'
             'Time: 0.123\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n')
        return raw,selector

    def test_39_valid_direct_stage_one(self):
        raw,sel=self.sample()
        receipt=host.verified_direct_stage_receipt(raw,sel)
        self.assertEqual(receipt['required_testcases'],1)
        self.assertEqual(receipt['failed_cases'],0)
        self.assertTrue(receipt['orchestrator_bypassed_for_persistent_state'])

    def test_40_stage_b_direct_receipt(self):
        raw,sel=self.sample('stageBInspectRecoveryAfterExternalForceStop')
        self.assertEqual(host.verified_direct_stage_receipt(raw,sel)['required_testcases'],1)

    def test_41_missing_success_refused(self):
        raw,sel=self.sample()
        with self.assertRaises(host.Refused):
            host.verified_direct_stage_receipt(raw.replace('INSTRUMENTATION_STATUS_CODE: 0','INSTRUMENTATION_STATUS_CODE: -2'),sel)

    def test_42_negative_runner_result_refused(self):
        raw,sel=self.sample()
        with self.assertRaises(host.Refused):
            host.verified_direct_stage_receipt(raw.replace('INSTRUMENTATION_CODE: -1','INSTRUMENTATION_CODE: 0'),sel)

    def test_43_wrong_test_name_refused(self):
        raw,sel=self.sample()
        with self.assertRaises(host.Refused):
            host.verified_direct_stage_receipt(raw.replace('stageAWriteDurablePendingOwnership','otherName'),sel)

    def test_44_two_tests_refused(self):
        raw,sel=self.sample()
        with self.assertRaises(host.Refused):
            host.verified_direct_stage_receipt(raw.replace('INSTRUMENTATION_STATUS_CODE: 1','INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 1'),sel)

    def test_45_failure_marker_refused(self):
        raw,sel=self.sample()
        with self.assertRaises(host.Refused):host.verified_direct_stage_receipt(raw+'FAILURES!!!\n',sel)

    def test_46_installer_is_exact_sized_and_preserves_data(self):
        import unittest.mock
        with tempfile.TemporaryDirectory() as t:
            with unittest.mock.patch.object(host,'ROOT',Path(t)):
                base=Path(t)/'android-app/app/build/outputs/apk'
                app=base/'debug/app-debug.apk';test=base/'androidTest/debug/app-debug-androidTest.apk'
                for file in (app,test):file.parent.mkdir(parents=True,exist_ok=True);file.write_bytes(b'x'*12000)
                calls=[]
                def fake_adb(serial,*cmd,**kw):
                    calls.append(cmd)
                    if cmd[0]=='install':return types.SimpleNamespace(returncode=0,stdout='Success\n')
                    if cmd[0]=='shell' and cmd[1]=='pm':return types.SimpleNamespace(returncode=0,stdout='package:/data/app/app.apk\n')
                    self.fail('Unreviewed device command')
                with unittest.mock.patch.object(host,'adb',fake_adb):
                    host.install_verified_pair_for_stage('emulator-5554',
                       {'app':{'sha256':e.sha(app.read_bytes())},
                        'test':{'sha256':e.sha(test.read_bytes())}})
                self.assertEqual([x[0:2] for x in calls[:2]],[('install','-r'),('install','-r')])
                self.assertNotIn('uninstall',str(calls))

    def test_47_stage_selector_pinned_and_clear_data_false(self):
        import unittest.mock
        raw,sel=self.sample()
        with unittest.mock.patch.object(host,'adb',return_value=types.SimpleNamespace(returncode=0,stdout=raw)) as fake:
            result=host.direct_stage('emulator-5554',sel)
            self.assertEqual(result['required_testcases'],1)
            args=fake.call_args.args
            self.assertEqual(args[1:10],('shell','am','instrument','-w','-e','clearPackageData','false','-e','class'))
            self.assertNotIn('gradle',args)

    def test_48_unknown_stage_refused_without_device_access(self):
        import unittest.mock
        with unittest.mock.patch.object(host,'adb') as fake:
            with self.assertRaises(host.Refused):
                host.direct_stage('emulator-5554','io.github.mikeceead.xbscompanion.Evil#escape')
            fake.assert_not_called()

if __name__=='__main__':unittest.main(verbosity=2)
