import importlib.util
import unittest
from pathlib import Path

SOURCE=Path(__file__).with_name('r03c_android_readonly_probe.py')
spec=importlib.util.spec_from_file_location('r03c_probe',SOURCE)
p=importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)

class R03CProbeTests(unittest.TestCase):
    def runner(self, serial, args):
        cmds={
         ('getprop','ro.build.version.sdk'):'36',
         ('getprop','ro.product.cpu.abi'):'arm64-v8a',
         ('cat','/sys/class/power_supply/battery/status'):'Charging',
         ('cat','/sys/class/power_supply/battery/capacity'):'75',
         ('dumpsys','power'):'Power Manager:\n mWakefulness=Awake\n'
        }
        v=cmds.get(tuple(args))
        if v is None:raise AssertionError('unsafe/unrecognized command '+repr(args))
        return v
    def test_accept_only_readonly_inventory(self):
        result=p.probe('localhost:5555',self.runner)
        self.assertEqual(result['sdk'],36)
        self.assertEqual(result['device_mutation_commands'],0)
        self.assertEqual(result['wakefulness'],'Awake')
        self.assertIn('NOT_ROOT_ACCEPTANCE',result['status'])
    def test_reject_wrong_api(self):
        def run(serial,args):return '35' if args==['getprop','ro.build.version.sdk'] else self.runner(serial,args)
        with self.assertRaises(p.ProbeRefusal):p.probe('localhost:5555',run)
    def test_reject_invalid_serial(self):
        with self.assertRaises(p.ProbeRefusal):p.probe('x; su -c id',self.runner)
    def test_ambiguous_wakefulness(self):
        with self.assertRaises(p.ProbeRefusal):p.parse_wakefulness('mWakefulness=Awake\nmWakefulness=Asleep')
    def test_unknown_wakefulness(self):
        with self.assertRaises(p.ProbeRefusal):p.parse_wakefulness('mWakefulness=Dreaming')
    def test_invalid_capacity(self):
        def run(serial,args):return '150' if args==['cat','/sys/class/power_supply/battery/capacity'] else self.runner(serial,args)
        with self.assertRaises(p.ProbeRefusal):p.probe('localhost:5555',run)

if __name__ == '__main__':unittest.main()
