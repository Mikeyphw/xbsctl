#!/usr/bin/env python3
"""R03D: read-only, tamper-evident Android 16 observation chronology.

This never changes device state or makes a root call. Real transitions must be
created by the user or device, never by the probe. A hash chain detects
accidental/trivial modification but is NOT a digital signature or root proof.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import time

import r03c_android_readonly_probe as sensor

SCHEMA = 'xbs-r03d-readonly-chronology/v1'
ACK = sensor.ACK
MAX_SAMPLES = 240
MAX_FILE = 2_000_000
ZERO_HASH = '0' * 64
VALID_WAKE = {'Awake', 'Asleep'}
VALID_BATTERY = {'charging', 'discharging', 'not charging', 'full'}


class ChronologyRefusal(Exception):
    pass


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('ascii')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def _safe_parent(path):
    """Reject symlinks in existing path components; never silently resolve them."""
    path = Path(os.path.abspath(os.path.expanduser(str(path))))
    cur = Path(path.anchor)
    for part in path.parts[1:-1]:
        cur = cur / part
        try:
            mode = cur.lstat().st_mode
        except FileNotFoundError:
            raise ChronologyRefusal('parent directory must already exist: ' + str(cur))
        if not stat.S_ISDIR(mode):
            raise ChronologyRefusal('unsafe parent component: ' + str(cur))
    return path


def _regular(path, max_bytes):
    p = _safe_parent(path)
    try:
        st = p.lstat()
    except OSError as exc:
        raise ChronologyRefusal('missing or unreadable report file: '+p.name) from exc
    if not stat.S_ISREG(st.st_mode) or st.st_size <= 0 or st.st_size > max_bytes:
        raise ChronologyRefusal('invalid report file size/type: '+p.name)
    try:
        fd = os.open(str(p), os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
        with os.fdopen(fd, 'rb') as f:
            opened = os.fstat(f.fileno())
            if opened.st_ino != st.st_ino or opened.st_dev != st.st_dev or not stat.S_ISREG(opened.st_mode):
                raise ChronologyRefusal('report file changed while reading: '+p.name)
            content = f.read(max_bytes+1)
    except OSError as exc:
        raise ChronologyRefusal('cannot read report file: '+p.name) from exc
    if len(content) > max_bytes:
        raise ChronologyRefusal('oversized report file: '+p.name)
    return content


def _create_file(path, raw):
    fd = os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    with os.fdopen(fd, 'wb') as f:
        f.write(raw)
        f.flush()
        os.fsync(f.fileno())


def _sync_dir(directory):
    fd = os.open(str(directory), os.O_RDONLY | getattr(os, 'O_DIRECTORY', 0))
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _validate_settings(count, interval, low, release):
    if type(count) is not int or not (2 <= count <= MAX_SAMPLES):
        raise ChronologyRefusal('samples must be between 2 and 240')
    if type(interval) not in (int, float) or not (0.1 <= interval <= 600):
        raise ChronologyRefusal('interval must be between 0.1 and 600 seconds')
    if (count-1)*interval > 7200:
        raise ChronologyRefusal('sample session exceeds two hours')
    if type(low) is not int or type(release) is not int or not (0 <= low < release <= 100):
        raise ChronologyRefusal('invalid low-power hysteresis thresholds')


def _observation(value):
    if not isinstance(value, dict):
        return {'valid': False, 'error': 'invalid observation type'}
    if value.get('device_mutation_commands') != 0:
        raise ChronologyRefusal('device mutation count is nonzero')
    b = value.get('battery_state')
    pct = value.get('battery_percent')
    if b not in VALID_BATTERY or type(pct) is not int or not 0 <= pct <= 100:
        return {'valid': False, 'error': 'battery observation is incomplete or invalid'}
    w = value.get('wakefulness')
    if w is not None and w not in VALID_WAKE:
        return {'valid': False, 'error': 'unsupported wakefulness observation'}
    return {'valid': True, 'battery_state': b, 'battery_percent': pct,
            'wakefulness': w, 'observation_source': value.get('observation_source'),
            'warnings': [str(x)[:300] for x in value.get('warnings', [])[:5]]}


def _sample_payload(index, elapsed_ms, wall_time, observation, previous_hash):
    core = {'schema': SCHEMA, 'index': index, 'elapsed_ms': elapsed_ms,
            'observed_at_utc': wall_time, 'observation': observation,
            'previous_sha256': previous_hash}
    return dict(core, sha256=digest(bytes.fromhex(previous_hash)+canonical(core)))


def summarize(rows, settings, session_bytes):
    transitions = []
    unknown = 0
    known_wake = 0
    gaps = 0
    previous = None
    low_state = None
    max_gap_ms = int(settings['interval_seconds'] * 2500)
    for row in rows:
        obs = row['observation']
        if not obs.get('valid'):
            unknown += 1
            previous = None
            low_state = None
            continue
        wake = obs['wakefulness']
        if wake is not None:
            known_wake += 1
        if previous is not None:
            before = previous['observation']
            delta = row['elapsed_ms'] - previous['elapsed_ms']
            if delta > max_gap_ms:
                gaps += 1
                # An unobserved interval is not proof of a specific transition.
                previous = row
                low_state = None
                continue
            if before['wakefulness'] in VALID_WAKE and wake in VALID_WAKE and before['wakefulness'] != wake:
                transitions.append({'index': row['index'], 'kind': 'wakefulness_changed',
                                    'from': before['wakefulness'], 'to': wake})
            if before['battery_state'] != obs['battery_state']:
                transitions.append({'index': row['index'], 'kind': 'battery_status_changed',
                                    'from': before['battery_state'], 'to': obs['battery_state']})
        pct = obs['battery_percent']
        new_low = True if pct <= settings['low_threshold'] else False if pct >= settings['release_threshold'] else low_state
        if low_state is not None and new_low is not None and low_state != new_low:
            transitions.append({'index': row['index'], 'kind': 'low_power_hysteresis_changed',
                                'from': low_state, 'to': new_low})
        low_state = new_low
        previous = row
    return {'schema': SCHEMA, 'status': ('READ_ONLY_CHRONOLOGY_COMPLETE_NOT_ROOT_ACCEPTANCE'
              if len(rows) == settings['requested_samples'] and unknown == 0 and known_wake == len(rows) and gaps == 0
              else 'READ_ONLY_CHRONOLOGY_PARTIAL_NOT_ROOT_ACCEPTANCE'),
            'samples': len(rows), 'requested_samples': settings['requested_samples'],
            'wakefulness_known': known_wake, 'unavailable_samples': unknown,
            'unobserved_time_gaps': gaps, 'transitions': transitions,
            'last_elapsed_ms': rows[-1]['elapsed_ms'] if rows else 0,
            'session_sha256': digest(session_bytes),
            'last_sample_hash': rows[-1]['sha256'] if rows else ZERO_HASH,
            'device_mutation_commands': 0,
            'root_acceptance': 'NOT_QUALIFIED',
            'limitations': 'Read-only observations only. No root actions, daemon transitions, or real device acceptance are verified.'}


def collect(serial, acknowledgement, output, count=30, interval=5.0,
            low=20, release=25, probe_fn=None, clock=None, sleep=None, utc=None):
    if acknowledgement != ACK:
        raise ChronologyRefusal('explicit read-only authorization is required')
    if not sensor.SERIAL.fullmatch(serial):
        raise ChronologyRefusal('invalid ADB serial')
    _validate_settings(count, interval, low, release)
    probe_fn = probe_fn or sensor.probe
    clock = clock or time.monotonic
    sleep = sleep or time.sleep
    utc = utc or (lambda: datetime.now(timezone.utc).isoformat(timespec='milliseconds'))
    root = _safe_parent(output)
    try:
        root.mkdir(mode=0o700)
    except FileExistsError as exc:
        raise ChronologyRefusal('report directory already exists; refusing overwrite') from exc
    _sync_dir(root.parent)
    settings = {'schema': SCHEMA, 'requested_samples': count, 'interval_seconds': interval,
                'low_threshold': low, 'release_threshold': release,
                'created_utc': utc(), 'device_api': 36, 'device_abi': 'arm64-v8a',
                'read_only': True, 'user_generated_transitions_only': True}
    session_bytes = canonical(settings) + b'\n'
    _create_file(root/'session.json', session_bytes)
    fd = os.open(str(root/'samples.jsonl'), os.O_WRONLY|os.O_CREAT|os.O_EXCL|getattr(os,'O_NOFOLLOW',0), 0o600)
    rows=[]
    prev=ZERO_HASH
    anchor = clock()
    try:
        with os.fdopen(fd, 'wb') as stream:
            for index in range(count):
                sleep(max(0, anchor+index*interval-clock()))
                observed = clock()
                try:
                    obs = _observation(probe_fn(serial))
                except sensor.ProbeRefusal as exc:
                    obs = {'valid': False, 'error': str(exc)[:300]}
                row = _sample_payload(index, int(round((observed-anchor)*1000)), utc(), obs, prev)
                stream.write(canonical(row)+b'\n')
                stream.flush()
                os.fsync(stream.fileno())
                rows.append(row)
                prev = row['sha256']
    except BaseException:
        # A missing result.json is an explicit, verifiable incomplete session.
        _sync_dir(root)
        raise
    report = summarize(rows, settings, session_bytes)
    report['samples_file_sha256'] = digest((root/'samples.jsonl').read_bytes())
    _create_file(root/'result.json', canonical(report)+b'\n')
    _sync_dir(root)
    return report


def verify(directory):
    root = _safe_parent(directory)
    try:
        if not stat.S_ISDIR(root.lstat().st_mode):
            raise ChronologyRefusal('session directory is not a directory')
    except FileNotFoundError as exc:
        raise ChronologyRefusal('session directory missing') from exc
    session_bytes = _regular(root/'session.json', 10_000)
    settings = json.loads(session_bytes)
    if settings.get('schema') != SCHEMA or settings.get('read_only') is not True:
        raise ChronologyRefusal('invalid read-only session metadata')
    _validate_settings(settings['requested_samples'], settings['interval_seconds'], settings['low_threshold'], settings['release_threshold'])
    samples_bytes = _regular(root/'samples.jsonl', MAX_FILE)
    raw_lines = samples_bytes.splitlines()
    if not (1 <= len(raw_lines) <= settings['requested_samples']):
        raise ChronologyRefusal('sample count out of bounds')
    previous = ZERO_HASH
    elapsed = -1
    rows=[]
    for index, line in enumerate(raw_lines):
        if len(line)>6000:
            raise ChronologyRefusal('oversized individual sample')
        item = json.loads(line)
        if not isinstance(item, dict) or item.get('schema') != SCHEMA or item.get('index') != index or item.get('previous_sha256') != previous:
            raise ChronologyRefusal('chronology index, schema, or hash-chain predecessor mismatch')
        if type(item.get('elapsed_ms')) is not int or item['elapsed_ms'] < elapsed or item['elapsed_ms'] < 0:
            raise ChronologyRefusal('nonmonotonic or invalid observation clock')
        elapsed = item['elapsed_ms']
        value = dict(item)
        supplied = value.pop('sha256', None)
        if supplied != digest(bytes.fromhex(previous)+canonical(value)):
            raise ChronologyRefusal('sample hash mismatch at index '+str(index))
        # Never interpret forged claims of mutated-device counts as sensor data.
        o = item.get('observation')
        if not isinstance(o, dict):
            raise ChronologyRefusal('invalid sample observation')
        if o.get('valid') is True:
            if o.get('battery_state') not in VALID_BATTERY or type(o.get('battery_percent')) is not int or not 0 <= o['battery_percent'] <= 100 or o.get('wakefulness') not in VALID_WAKE|{None}:
                raise ChronologyRefusal('invalid sensor values in saved sample')
        elif o.get('valid') is not False:
            raise ChronologyRefusal('invalid sensor status in saved sample')
        rows.append(item)
        previous=supplied
    actual = json.loads(_regular(root/'result.json', 100_000))
    expected = summarize(rows, settings, session_bytes)
    expected['samples_file_sha256'] = digest(samples_bytes)
    if actual != expected:
        raise ChronologyRefusal('session result does not match independently verified samples')
    return actual


def main(argv=None):
    parser=argparse.ArgumentParser(description='R03D read-only Android chronology and offline verification')
    actions=parser.add_mutually_exclusive_group(required=True)
    actions.add_argument('--collect-chronology', action='store_true')
    actions.add_argument('--verify-chronology', metavar='SESSION_DIR')
    parser.add_argument('--serial')
    parser.add_argument('--ack')
    parser.add_argument('--output')
    parser.add_argument('--samples', type=int, default=30)
    parser.add_argument('--interval', type=float, default=5.)
    parser.add_argument('--low-threshold', type=int, default=20)
    parser.add_argument('--release-threshold', type=int, default=25)
    args=parser.parse_args(argv)
    if args.collect_chronology:
        if not args.serial or not args.ack or not args.output:
            raise ChronologyRefusal('collection requires --serial --ack --output')
        result=collect(args.serial,args.ack,args.output,args.samples,args.interval,args.low_threshold,args.release_threshold)
    else:
        if args.serial or args.ack or args.output:
            raise ChronologyRefusal('offline verification must not request ADB access')
        result=verify(args.verify_chronology)
    print(json.dumps(result,indent=2,sort_keys=True))
    return result


if __name__=='__main__':
    try:
        main()
    except (ChronologyRefusal, ValueError, KeyError, TypeError, json.JSONDecodeError, OSError) as exc:
        print('XBS_R03D_READ_ONLY_CHRONOLOGY_REFUSED: '+str(exc),file=sys.stderr)
        sys.exit(1)
