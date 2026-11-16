"""GX05 real host parser negatives and bounded seven-sample integration."""
import sys,unittest,contextlib,io
from types import SimpleNamespace
from pathlib import Path
sys.path.insert(0,str(Path(__file__).parent))
import gx05_hosted_measure as h
import gx05_preflight as p

class FakeADB:
    def __init__(self,total='TotalTime: 2183',mem='TOTAL PSS: 124,500'):
        self.total=total;self.mem=mem;self.calls=[]
    def __call__(self,serial,*args):
        self.calls.append(args)
        if args[:3]==('shell','am','force-stop'):return SimpleNamespace(stdout='')
        if args[:3]==('shell','am','start'):return SimpleNamespace(stdout=self.total)
        if args[:3]==('shell','dumpsys','meminfo'):return SimpleNamespace(stdout=self.mem)
        raise AssertionError('Unexpected ADB '+repr(args))

class HostedProbeTests(unittest.TestCase):
    def test_01_real_total_time(self):self.assertEqual(h.parse_total_time('Status: ok\nTotalTime: 324\n'),324)
    def test_02_duplicate_total_time_refused(self):
        with self.assertRaises(p.Refused):h.parse_total_time('TotalTime: 12\nTotalTime: 12')
    def test_03_missing_total_time_refused(self):
        with self.assertRaises(p.Refused):h.parse_total_time('Status: ok')
    def test_04_fake_total_time_refused(self):
        with self.assertRaises(p.Refused):h.parse_total_time('TotalTime: -2')
    def test_05_pss_summary(self):self.assertEqual(h.parse_pss('TOTAL PSS: 124,500\n'),124500)
    def test_06_pss_total_row(self):self.assertEqual(h.parse_pss('TOTAL     120000  310   450  5200  0 0\n'),120000)
    def test_07_missing_pss(self):
        with self.assertRaises(p.Refused):h.parse_pss('No process memory')
    def test_08_rootless_actual_host_observations(self):
        fake=FakeADB()
        with contextlib.redirect_stdout(io.StringIO()):
            x=h.collect('emulator-5554',fake,'io.github.mikeceead.xbscompanion',
               {'api_level':36,'abi':'x86_64','emulator_only':True})
        self.assertEqual(len(x['cold_launch_ms']),7)
        self.assertEqual(len(fake.calls),21)
        self.assertEqual([y[1:3] for y in fake.calls[:3]], [('am','force-stop'),('am','start'),('dumpsys','meminfo')])
    def test_09_non_emulator_refused(self):
        fake=FakeADB()
        with self.assertRaises(p.Refused):h.collect('emulator-5554',fake,'io.github.mikeceead.xbscompanion',
           {'api_level':36,'abi':'x86_64','emulator_only':False})
        self.assertEqual(fake.calls,[])
    def test_10_invalid_package_refused(self):
        fake=FakeADB()
        with self.assertRaises(p.Refused):h.collect('emulator-5554',fake,';su -c',
           {'api_level':36,'abi':'x86_64','emulator_only':True})
        self.assertEqual(fake.calls,[])
    def test_11_no_fake_startup_samples(self):
        fake=FakeADB(total='Starting: Intent')
        with self.assertRaises(p.Refused):h.collect('emulator-5554',fake,'io.github.mikeceead.xbscompanion',
           {'api_level':36,'abi':'x86_64','emulator_only':True})
    def test_12_no_fake_memory(self):
        fake=FakeADB(mem='unknown')
        with self.assertRaises(p.Refused):h.collect('emulator-5554',fake,'io.github.mikeceead.xbscompanion',
           {'api_level':36,'abi':'x86_64','emulator_only':True})
if __name__=='__main__':unittest.main(verbosity=2)
