"""GX05 fail-closed controls: no root, emulator, network or local Gradle."""
import copy
import hashlib
import sys
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).parent))
import gx05_preflight as p

SHA='a'*40
RUN='43772490865'; ATT='1'

def sample():
    identity={'source_sha':SHA,'run_id':RUN,'run_attempt':ATT,'event':'pull_request','hosted':True}
    perf={'cold_launch_ms':[1900,2100,2600,2400,2300,3000,2700],'pss_kib':[112000]*7,
          'sample_source':'adb-am-start-W-TotalTime','api':36,'abi':'x86_64','emulator':True,
          'physical_qualified':False,'battery_savings_qualified':False}
    artifacts=[{'path':n,'sha256':hashlib.sha256(n.encode()).hexdigest(),'size':42,
                'source_sha':SHA,'run_id':RUN,'run_attempt':ATT} for n in ('metrics.json','security.xml')]
    security={'instrumented_count':7,'failed':0,'skipped':0,'exported_components_verified':True,'untrusted_receiver_tests':True}
    return {'identity':identity,'performance':perf,'artifacts':artifacts,'security':security}

class GX05Tests(unittest.TestCase):
    def test_01_real_percentile(self):self.assertEqual(p.actual_percentile([1,2,3,4,5,6,7],95),7)
    def test_02_median(self):self.assertEqual(p.actual_percentile([9,1,3,8,2,5,6],50),5)
    def test_03_sample_quantity_refused(self):
        with self.assertRaises(p.Refused):p.actual_percentile([2]*6,95)
    def test_04_bool_not_number(self):
        with self.assertRaises(p.Refused):p.actual_percentile([2]*6+[True],95)
    def test_05_sample_zero(self):
        with self.assertRaises(p.Refused):p.actual_percentile([2]*6+[0],95)
    def test_06_sample_negative(self):
        with self.assertRaises(p.Refused):p.actual_percentile([2]*6+[-1],95)
    def test_07_sample_floating(self):
        with self.assertRaises(p.Refused):p.actual_percentile([2]*6+[1.3],95)
    def test_08_large_sample_refused(self):
        with self.assertRaises(p.Refused):p.actual_percentile([2]*6+[120000],95)
    def test_09_unknown_percentile_refused(self):
        with self.assertRaises(p.Refused):p.actual_percentile([2]*7,99)
    def test_10_full_evidence(self):
        x=p.evidence(**sample());self.assertEqual(x['security_test_count'],7);self.assertFalse(x['physical_root_qualified'])
    def test_11_missing_identity_key(self):
        s=sample();del s['identity']['run_attempt'];self.refused(s)
    def test_12_wrong_head(self):
        s=sample();s['identity']['source_sha']='bad';self.refused(s)
    def test_13_branch_event(self):
        s=sample();s['identity']['event']='push';self.refused(s)
    def test_14_not_hosted(self):
        s=sample();s['identity']['hosted']=False;self.refused(s)
    def test_15_missing_run(self):
        s=sample();s['identity']['run_id']='0';self.refused(s)
    def test_16_missing_attempt(self):
        s=sample();s['identity']['run_attempt']='';self.refused(s)
    def test_17_duplicate_artifact(self):
        s=sample();s['artifacts'].append(copy.deepcopy(s['artifacts'][0]));self.refused(s)
    def test_18_mismatch_artifact_run(self):
        s=sample();s['artifacts'][0]['run_id']='1';self.refused(s)
    def test_19_mismatch_artifact_attempt(self):
        s=sample();s['artifacts'][0]['run_attempt']='2';self.refused(s)
    def test_20_mismatch_artifact_head(self):
        s=sample();s['artifacts'][0]['source_sha']='b'*40;self.refused(s)
    def test_21_invalid_artifact_hash(self):
        s=sample();s['artifacts'][0]['sha256']='a';self.refused(s)
    def test_22_zero_artifact_size(self):
        s=sample();s['artifacts'][0]['size']=0;self.refused(s)
    def test_23_directory_escape(self):
        s=sample();s['artifacts'][0]['path']='../../token';self.refused(s)
    def test_24_dot_git_escape(self):
        s=sample();s['artifacts'][0]['path']='.git/config';self.refused(s)
    def test_25_windows_escape(self):
        s=sample();s['artifacts'][0]['path']='foo\\bar';self.refused(s)
    def test_26_empty_artifact_set(self):
        s=sample();s['artifacts']=[];self.refused(s)
    def test_27_missing_security(self):
        s=sample();s['security']['instrumented_count']=0;self.refused(s)
    def test_28_skipped_security(self):
        s=sample();s['security']['skipped']=1;self.refused(s)
    def test_29_failed_security(self):
        s=sample();s['security']['failed']=1;self.refused(s)
    def test_30_unverified_export(self):
        s=sample();s['security']['exported_components_verified']=False;self.refused(s)
    def test_31_unverified_attack_tests(self):
        s=sample();s['security']['untrusted_receiver_tests']=False;self.refused(s)
    def test_32_fake_measurement(self):
        s=sample();s['performance']['sample_source']='fixture';self.refused(s)
    def test_33_fake_emulator(self):
        s=sample();s['performance']['emulator']=False;self.refused(s)
    def test_34_wrong_api(self):
        s=sample();s['performance']['api']=35;self.refused(s)
    def test_35_invalid_abi(self):
        s=sample();s['performance']['abi']='arm64-v8a';self.refused(s)
    def test_36_physical_overclaim(self):
        s=sample();s['performance']['physical_qualified']=True;self.refused(s)
    def test_37_battery_overclaim(self):
        s=sample();s['performance']['battery_savings_qualified']=True;self.refused(s)
    def test_38_mismatch_mem_count(self):
        s=sample();s['performance']['pss_kib'].pop();self.refused(s)
    def test_39_memory_zero(self):
        s=sample();s['performance']['pss_kib'][0]=0;self.refused(s)
    def test_40_bad_p50(self):
        s=sample();s['performance']['cold_launch_ms']=[10000]*7;self.refused(s)
    def test_41_bad_p95(self):
        s=sample();s['performance']['cold_launch_ms']=[4000]*6+[16000];self.refused(s)
    def test_42_bad_memory(self):
        s=sample();s['performance']['pss_kib'][0]=530000;self.refused(s)
    def test_43_no_release_from_hosted(self):
        x=p.release_gate(security=True,performance=True,physical=False,signing=False,original_cases=[])
        self.assertFalse(x['release_allowed']);self.assertIn('physical',x['missing'])
    def test_44_redaction(self):
        x=p.redact('secret=hello password=abc /data/adb/modules/my_module')
        self.assertNotIn('hello',x);self.assertNotIn('abc',x);self.assertNotIn('my_module',x)
    def test_45_inventory_needs_consent(self):
        s=inventory();s['user_consent']=False;self.assertRaises(p.Refused,p.physical_inventory,s)
    def test_46_inventory_needs_disposable(self):
        s=inventory();s['device_is_disposable']=False;self.assertRaises(p.Refused,p.physical_inventory,s)
    def test_47_inventory_invalid_hash(self):
        s=inventory();s['module_digest']='';self.assertRaises(p.Refused,p.physical_inventory,s)
    def test_48_inventory_valid_but_no_physical_proof(self):
        x=p.physical_inventory(inventory());self.assertTrue(x['read_only_inventory_validated']);self.assertFalse(x['physical_qualified'])
    def test_49_no_silent_extra_receipt(self):
        s=sample();s['security']['extra']=True;self.refused(s)
    def test_50_bounded_report(self):self.assertLessEqual(len(p.redact('a'*1000)),420)
    def refused(self,value):
        with self.assertRaises(p.Refused):p.evidence(**value)

def inventory():
    return {'api':36,'abi':'arm64-v8a','root_manager':'kernelsu','module_id':'xbs',
            'module_version':'v1.0','module_digest':'a'*64,'device_is_disposable':True,'user_consent':True}
if __name__=='__main__':unittest.main(verbosity=2)
