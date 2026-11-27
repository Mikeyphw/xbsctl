"""Actual R03C HF01 probe contracts; no device commands."""
import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SOURCE = Path(__file__).with_name('r03c_android_readonly_probe.py')
spec = importlib.util.spec_from_file_location('r03c_probe_hf01', SOURCE)
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)

class R03CProbeHF01Tests(unittest.TestCase):
    def runner(self, serial, args):
        cases = {
            ('getprop','ro.build.version.sdk'): '36',
            ('getprop','ro.product.cpu.abi'): 'arm64-v8a',
            ('cat','/sys/class/power_supply/battery/status'): 'Charging',
            ('cat','/sys/class/power_supply/battery/capacity'): '75',
            ('dumpsys','power'): 'Power Manager:\n mWakefulness=Awake\n',
            ('dumpsys','input_method'): 'mInteractive=true',
            ('dumpsys','display'): 'mScreenState=ON',
        }
        val = cases.get(tuple(args))
        if val is None:
            raise AssertionError('Unsafe device command: ' + repr(args))
        return val

    def test_full_awake_only_from_power(self):
        result = p.probe('localhost:5555', self.runner)
        self.assertEqual(result['status'], 'READ_ONLY_SENSOR_PROBE_PASS_NOT_ROOT_ACCEPTANCE')
        self.assertEqual(result['wakefulness'], 'Awake')
        self.assertIsNone(result['interactive'])
        self.assertEqual(result['observation_source'], 'dumpsys power')
        self.assertEqual(result['device_mutation_commands'], 0)

    def test_large_legitimate_power_dump_is_accepted(self):
        with patch.object(p.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, 'x' * 72000 + '\nmWakefulness=Asleep\n', '')):
            actual = p.exec_adb('localhost:5555', ['dumpsys', 'power'])
        self.assertIn('mWakefulness=Asleep', actual)
        self.assertEqual(p.parse_wakefulness(actual), 'Asleep')

    def test_failed_adb_read_has_stderr_and_exit_code(self):
        with patch.object(p.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, '', 'Permission denied by service')):
            with self.assertRaisesRegex(p.ProbeRefusal, r'exit=1.*Permission denied'):
                p.exec_adb('localhost:5555', ['dumpsys','power'])

    def test_power_denied_fallback_reports_partial_not_awake(self):
        calls = []
        def run(serial, args):
            calls.append(tuple(args))
            if args == ['dumpsys','power']:
                raise p.ProbeRefusal('ADB read returned exit=1 for: dumpsys power (Permission denied)')
            return self.runner(serial,args)
        result = p.probe('localhost:5555', run)
        self.assertEqual(result['status'], 'READ_ONLY_SENSOR_PROBE_PARTIAL_NOT_ROOT_ACCEPTANCE')
        self.assertIsNone(result['wakefulness'])
        self.assertIs(result['interactive'], True)
        self.assertEqual(result['observation_source'], 'dumpsys input_method')
        self.assertIn(('dumpsys','input_method'),calls)
        self.assertNotIn(('dumpsys','display'),calls)
        self.assertTrue(any('Permission denied' in x for x in result['warnings']))

    def test_all_power_services_denied_returns_explicit_partial(self):
        def run(serial,args):
            if args[0] == 'dumpsys':
                raise p.ProbeRefusal('permission denied')
            return self.runner(serial,args)
        result = p.probe('localhost:5555',run)
        self.assertEqual(result['status'],'READ_ONLY_SENSOR_PROBE_PARTIAL_NOT_ROOT_ACCEPTANCE')
        self.assertIsNone(result['wakefulness'])
        self.assertIsNone(result['interactive'])
        self.assertIsNone(result['screen_state'])
        self.assertEqual(len(result['warnings']),3)

    def test_strict_mode_refuses_unavailable_wakefulness(self):
        def run(serial,args):
            if args[0]=='dumpsys':raise p.ProbeRefusal('service denied')
            return self.runner(serial,args)
        with self.assertRaisesRegex(p.ProbeRefusal,'strict wakefulness required'):
            p.probe('localhost:5555',run,require_wakefulness=True)

    def test_display_fallback_does_not_invent_wakefulness(self):
        def run(serial,args):
            if args == ['dumpsys','power'] or args == ['dumpsys','input_method']:
                raise p.ProbeRefusal('service denied')
            return self.runner(serial,args)
        r=p.probe('localhost:5555',run)
        self.assertIsNone(r['wakefulness'])
        self.assertEqual(r['screen_state'],'ON')
        self.assertEqual(r['observation_source'],'dumpsys display')

    def test_duplicate_or_conflicting_interactivity_rejected(self):
        for s in ('mInteractive=true\nmInteractive=false\n','mInteractive=true\nmInteractive=true\n'):
            with self.assertRaises(p.ProbeRefusal):p.parse_interactive(s)

    def test_conflicting_multi_display_state_rejected(self):
        with self.assertRaises(p.ProbeRefusal):p.parse_screen_state('mScreenState=ON\nmScreenState=OFF')

    def test_unsupported_wakefulness_not_substituted(self):
        with self.assertRaises(p.ProbeRefusal):p.parse_wakefulness('mWakefulness=Dozing')
        with self.assertRaises(p.ProbeRefusal):p.parse_wakefulness('mWakefulness=Awake\nmWakefulness=Asleep')

    def test_reject_wrong_api(self):
        def run(serial,args):
            return '35' if args == ['getprop','ro.build.version.sdk'] else self.runner(serial,args)
        with self.assertRaises(p.ProbeRefusal):p.probe('localhost:5555',run)

    def test_reject_invalid_serial_and_disallowed_adb_command(self):
        with self.assertRaises(p.ProbeRefusal):p.probe('x; su -c id',self.runner)
        with self.assertRaises(p.ProbeRefusal):p.exec_adb('localhost:5555',['settings','put','system','screen_brightness','10'])
        with self.assertRaises(p.ProbeRefusal):p.exec_adb('localhost:5555',['su','-c','id'])

    def test_invalid_battery_reading(self):
        def run(serial,args):
            return '150' if args == ['cat','/sys/class/power_supply/battery/capacity'] else self.runner(serial,args)
        with self.assertRaises(p.ProbeRefusal):p.probe('localhost:5555',run)

    def test_report_refuses_existing_path(self):
        with tempfile.TemporaryDirectory() as td:
            report = Path(td)/'probe.json'
            report.write_text('DO NOT OVERWRITE')
            with patch.object(p,'probe',return_value={'status':'SYNTHETIC','device_mutation_commands':0}):
                with self.assertRaisesRegex(p.ProbeRefusal,'report exists'):
                    p.main(['--serial','localhost:5555','--ack',p.ACK,'--report',str(report)])
            self.assertEqual(report.read_text(),'DO NOT OVERWRITE')

    def test_require_user_ack(self):
        with self.assertRaisesRegex(p.ProbeRefusal,'acknowledgement'):
            p.main(['--serial','localhost:5555','--ack','NO'])

    def test_too_large_output_is_refused_with_specific_reason(self):
        with patch.object(p.subprocess,'run',return_value=subprocess.CompletedProcess([],0,'x'*(p.MAX_OUTPUT_BYTES+1),'')):
            with self.assertRaisesRegex(p.ProbeRefusal,'exceeded 2 MiB'):
                p.exec_adb('localhost:5555',['dumpsys','power'])

if __name__ == '__main__': unittest.main()
