"""R03D deterministic read-only chronology contract tests, never needs ADB."""
from __future__ import annotations
import json
import os
from pathlib import Path
import tempfile
import unittest

import r03c_android_readonly_probe as sensor
import r03d_android_chronology as c


class FakeClock:
    def __init__(self): self.value=100.0
    def now(self): return self.value
    def sleep(self,d): self.value+=max(0,d)


def obs(wake='Awake', battery='discharging', pct=83):
    return {'status':'READ_ONLY_SENSOR_PROBE_PASS_NOT_ROOT_ACCEPTANCE',
            'device_mutation_commands':0,'wakefulness':wake,'battery_state':battery,
            'battery_percent':pct,'observation_source':'dumpsys power', 'warnings':[]}


class ChronologyTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.directory=Path(self.tmp.name)/'session'

    def collect(self, observations=None, count=None, clk=None, **kwargs):
        observations=observations or [obs('Awake'),obs('Asleep'),obs('Asleep','charging',81),obs('Awake','charging',81),obs('Awake','discharging',80)]
        clock=clk or FakeClock()
        values=iter(observations)
        result=c.collect('localhost:5555',sensor.ACK,self.directory,count or len(observations),1.0,
                          probe_fn=lambda serial:next(values),clock=clock.now,sleep=clock.sleep,
                          utc=lambda:'2026-11-12T22:00:00.000+00:00',**kwargs)
        return result

    def test_chronology_records_only_verified_wakefulness_and_battery_status(self):
        r=self.collect()
        self.assertEqual(r['status'],'READ_ONLY_CHRONOLOGY_COMPLETE_NOT_ROOT_ACCEPTANCE')
        self.assertEqual([(t['kind'],t['index']) for t in r['transitions']],
                         [('wakefulness_changed',1),('battery_status_changed',2),('wakefulness_changed',3),('battery_status_changed',4)])
        self.assertEqual(c.verify(self.directory),r)
        self.assertEqual(r['device_mutation_commands'],0)
        self.assertEqual(r['root_acceptance'],'NOT_QUALIFIED')

    def test_clock_is_monotonic_and_has_requested_cadence(self):
        self.collect()
        rows=[json.loads(x) for x in (self.directory/'samples.jsonl').read_text().splitlines()]
        self.assertEqual([x['elapsed_ms'] for x in rows],[0,1000,2000,3000,4000])

    def test_unknown_wakefulness_does_not_create_screen_transition(self):
        r=self.collect([obs('Awake'),obs(None),obs('Asleep')])
        self.assertEqual([x for x in r['transitions'] if x['kind']=='wakefulness_changed'],[])
        self.assertIn('PARTIAL',r['status'])

    def test_unknown_battery_reading_breaks_all_transition_continuity(self):
        seq=[obs('Awake','discharging'),obs('Awake','charging'),obs('Awake','discharging')]
        bad=lambda serial: (_ for _ in ()).throw(sensor.ProbeRefusal('sensor read unavailable'))
        readings=iter([lambda _: seq[0],bad,lambda _:seq[2]])
        clock=FakeClock()
        r=c.collect('localhost:5555',sensor.ACK,self.directory,3,1.,
                    probe_fn=lambda serial:next(readings)(serial),clock=clock.now,sleep=clock.sleep,
                    utc=lambda:'2026-11-12T22:00:00Z')
        self.assertEqual(r['unavailable_samples'],1)
        self.assertFalse(r['transitions'])
        self.assertEqual(c.verify(self.directory),r)

    def test_detects_missed_observation_gap_without_making_transition_claim(self):
        clock=FakeClock()
        def long_sleep(seconds): clock.value+=max(seconds,3.2)
        readings=iter([obs('Awake'),obs('Asleep')])
        r=c.collect('localhost:5555',sensor.ACK,self.directory,2,1.,
                    probe_fn=lambda serial:next(readings),
                    clock=clock.now,sleep=long_sleep,utc=lambda:'2026-11-12T22:00:00Z')
        # long sleep creates a 3.2s gap, regardless of source value
        self.assertEqual(r['unobserved_time_gaps'],1)
        self.assertFalse(r['transitions'])

    def test_low_power_hysteresis_transition_after_recovery(self):
        r=self.collect([obs(pct=19),obs(pct=22),obs(pct=25),obs(pct=19)],low=20,release=25)
        low=[x for x in r['transitions'] if x['kind']=='low_power_hysteresis_changed']
        self.assertEqual([(x['index'],x['to']) for x in low],[(2,False),(3,True)])

    def test_mid_band_without_prior_state_does_not_guess(self):
        r=self.collect([obs(pct=22),obs(pct=23)])
        self.assertEqual([x for x in r['transitions'] if x['kind']=='low_power_hysteresis_changed'],[])

    def test_battery_not_charging_not_equivalent_to_disconnected(self):
        r=self.collect([obs(battery='charging'),obs(battery='not charging')])
        self.assertEqual(r['transitions'][0]['kind'],'battery_status_changed')
        self.assertFalse(any('unplug' in x['kind'] for x in r['transitions']))

    def test_mutation_count_causes_refusal_and_incomplete_receipt(self):
        with self.assertRaisesRegex(c.ChronologyRefusal,'mutation count'):
            self.collect([obs(),dict(obs(),device_mutation_commands=1)])
        self.assertTrue((self.directory/'samples.jsonl').exists())
        with self.assertRaises(c.ChronologyRefusal): c.verify(self.directory)

    def test_directory_not_overwritten(self):
        self.collect()
        original=(self.directory/'samples.jsonl').read_bytes()
        with self.assertRaisesRegex(c.ChronologyRefusal,'already exists'): self.collect()
        self.assertEqual((self.directory/'samples.jsonl').read_bytes(),original)

    def test_rejects_wrong_ack_before_directory_creation(self):
        with self.assertRaisesRegex(c.ChronologyRefusal,'authorization'):
            c.collect('localhost:5555','bad',self.directory,count=2)
        self.assertFalse(self.directory.exists())

    def test_rejects_unsafe_serial(self):
        with self.assertRaises(c.ChronologyRefusal):
            c.collect('localhost:5555;su',sensor.ACK,self.directory,count=2)
        self.assertFalse(self.directory.exists())

    def test_rejects_linked_parent_directory(self):
        redirect=Path(self.tmp.name)/'redirect'
        redirect.mkdir()
        (Path(self.tmp.name)/'sym').symlink_to(redirect,target_is_directory=True)
        with self.assertRaisesRegex(c.ChronologyRefusal,'unsafe parent'):
            c.collect('localhost:5555',sensor.ACK,Path(self.tmp.name)/'sym'/'session',count=2)
        self.assertFalse((redirect/'session').exists())

    def test_rejects_invalid_thresholds_and_long_sessions(self):
        for kw in [dict(low=24,release=24),dict(low=26,release=25),dict(count=1),dict(count=241),dict(count=240,interval=100.0)]:
            with self.subTest(kw=kw), self.assertRaises(c.ChronologyRefusal):
                x=dict(serial='localhost:5555',acknowledgement=sensor.ACK,output=self.directory,count=2,interval=1.,low=20,release=25)
                x.update(kw)
                c.collect(**x)
        self.assertFalse(self.directory.exists())

    def test_hash_chain_mutated_sample_refused(self):
        self.collect()
        p=self.directory/'samples.jsonl'
        rows=p.read_text().splitlines()
        bad=json.loads(rows[1]);bad['observation']['wakefulness']='Awake';rows[1]=json.dumps(bad)
        p.write_text('\n'.join(rows)+'\n')
        with self.assertRaisesRegex(c.ChronologyRefusal,'sample hash'):
            c.verify(self.directory)

    def test_removed_sample_refused(self):
        self.collect()
        p=self.directory/'samples.jsonl';rows=p.read_text().splitlines();p.write_text('\n'.join(rows[:-1])+'\n')
        with self.assertRaises(c.ChronologyRefusal):c.verify(self.directory)

    def test_forged_transition_summary_refused(self):
        self.collect()
        p=self.directory/'result.json';report=json.loads(p.read_text());report['transitions'].append({'index':9,'kind':'root_pass'})
        p.write_text(json.dumps(report))
        with self.assertRaisesRegex(c.ChronologyRefusal,'does not match'):c.verify(self.directory)

    def test_corrupt_clock_refused_even_with_rehashed_line(self):
        self.collect()
        p=self.directory/'samples.jsonl';lines=p.read_text().splitlines()
        value=json.loads(lines[1]);value['elapsed_ms']=-1
        lines[1]=json.dumps(value)
        p.write_text('\n'.join(lines)+'\n')
        with self.assertRaisesRegex(c.ChronologyRefusal,'nonmonotonic'):c.verify(self.directory)

    def test_symlinked_result_file_refused(self):
        self.collect()
        p=self.directory/'result.json';copy=Path(self.tmp.name)/'copy';copy.write_bytes(p.read_bytes());p.unlink();p.symlink_to(copy)
        with self.assertRaisesRegex(c.ChronologyRefusal,'size/type'): c.verify(self.directory)

    def test_unfinished_session_refused(self):
        self.collect()
        (self.directory/'result.json').unlink()
        with self.assertRaises(c.ChronologyRefusal):c.verify(self.directory)

    def test_report_no_device_identifier_or_fabricated_acceptance(self):
        self.collect()
        data=b''.join((self.directory/name).read_bytes() for name in ('session.json','samples.jsonl','result.json'))
        self.assertNotIn(b'localhost:5555',data)
        self.assertNotIn(b'PHYSICAL_VERIFIED',data)

    def test_original_probe_disallows_all_device_mutations(self):
        for args in (['su'],['settings','put','global','foo','1'],['input','keyevent','26'],['dumpsys','battery','set','level','1'],['shell','reboot']):
            with self.subTest(args=args), self.assertRaises(sensor.ProbeRefusal):
                sensor.exec_adb('localhost:5555',args)

    def test_rejects_device_verification_options_in_offline_mode(self):
        with self.assertRaisesRegex(c.ChronologyRefusal,'offline verification'):
            c.main(['--verify-chronology',str(self.directory),'--serial','localhost:5555'])


if __name__=='__main__':unittest.main()
