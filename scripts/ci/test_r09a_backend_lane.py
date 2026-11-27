#!/usr/bin/env python3
"""R09A fail-closed parser and evidence contract tests, stdlib only."""
from __future__ import annotations
import importlib.util
import json
from pathlib import Path
import unittest

HERE=Path(__file__).resolve().parent
SPEC=importlib.util.spec_from_file_location('r09a_backend',HERE/'r09a_backend_lane.py')
assert SPEC and SPEC.loader
D=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(D)

PREFIX='github.com/Mikeyphw/xbsctl/internal/'

def event(action,package,test=None):
    row={'Action':action,'Package':PREFIX+package}
    if test is not None:row['Test']=test
    return json.dumps(row)

def positive():
    out=[]
    for pkg,names in {'config':['TestR09AConfigTypeNegatives','FuzzR09AEventName','FuzzR09ARequiredPathTypes'],
                      'control':['TestR09AConcurrentQueueIDs','FuzzR09ARequestID'],
                      'eventbridge':['TestR09ADigestMalformedInputs','FuzzR09AHexDigest','FuzzR09ACanonicalJSON']}.items():
        for test in names:out.append(event('pass',pkg,test))
        out.append(event('pass',pkg))
    return '\n'.join(out)

class BackendLaneTest(unittest.TestCase):
    def test_positive_case_receipts(self):
        d=D.parse_events(positive())
        self.assertEqual(d['test_cases'],8)
        self.assertEqual(d['passed_packages'],3)
        self.assertEqual(d['failed_cases'],0)

    def test_missing_required_target_refuses(self):
        s=positive().replace(event('pass','eventbridge','FuzzR09AHexDigest'),'')
        with self.assertRaises(D.Refused):D.parse_events(s)

    def test_bad_json_refuses(self):
        with self.assertRaises(D.Refused):D.parse_events(positive()+'\nnot-json')

    def test_skipped_and_failed_go_refuse(self):
        for mutant in [event('skip','config','FuzzR09AEventName'),event('fail','control','TestR09AConcurrentQueueIDs')]:
            with self.subTest(mutant=mutant):
                with self.assertRaises(D.Refused):D.parse_events(positive()+'\n'+mutant)

    def test_duplicate_pass_refuses(self):
        with self.assertRaises(D.Refused):D.parse_events(positive()+'\n'+event('pass','config','FuzzR09AEventName'))

    def test_wrong_package_identity_refuses(self):
        bad=positive().replace(PREFIX,'example.com/fork/internal/')
        with self.assertRaises(D.Refused):D.parse_events(bad)

    def test_fuzz_executions_are_real(self):
        good='fuzz: elapsed: 0s, gathering baseline coverage: 12/12 completed\nfuzz: elapsed: 2s, execs: 542 (271/sec), new interesting: 1 (total: 13)\nPASS\nok  github.com/Mikeyphw/xbsctl/internal/config 2.1s\n'
        self.assertEqual(D.parse_fuzz(good,'config/fuzz'),542)
        for bad in [good.replace('execs: 542','execs: 0'),good.replace('PASS','FAIL'),good.replace('ok  github','notok  github')]:
            with self.assertRaises(D.Refused):D.parse_fuzz(bad,'config/fuzz')

    def test_coverage_is_numeric_positive(self):
        self.assertEqual(D.parse_coverage('total: (statements) 7.1%\n'),7.1)
        for bad in ['total: (statements) 0.0%', 'total: (statements) 200%', '', 'total: (statements) 8%\ntotal: (statements) 9%']:
            with self.assertRaises(D.Refused):D.parse_coverage(bad)

    def test_repeat_runner_never_passes_zero_or_skip(self):
        s='\n'.join([event('pass',pkg,'TestR09A'+str(i)) for pkg in D.PACKAGES for i in range(3)]+[event('pass',pkg) for pkg in D.PACKAGES])
        self.assertEqual(D.parse_repeat_events(s),9)
        for bad in [s+'\n'+event('skip','control','TestR09A'),s+'\n'+event('fail','control','TestR09A'),s.replace('TestR09A','NotTestR09A')]:
            with self.assertRaises(D.Refused):D.parse_repeat_events(bad)

    def test_full_fuzz_inventory_is_exact(self):
        self.assertEqual(sum(map(len,D.FUZZ.values())),5)
        self.assertEqual(set(D.FUZZ),set(D.PACKAGES))
        for fn in [n for names in D.FUZZ.values() for n in names]:self.assertTrue(fn.startswith('FuzzR09A'))

    def test_workflow_has_required_backend_gate(self):
        workflow=(HERE.parents[1]/'.github/workflows/devtool-xbs-ci.yml').read_text()
        self.assertEqual(workflow.count('name: R09A bounded backend fuzz, repeat and coverage'),1)
        self.assertIn('python3 -B scripts/ci/r09a_backend_lane.py --hosted --repo .',workflow)
        self.assertIn('dist/r09a-backend-qualification.json',workflow)
        self.assertIn('dist/r09a-backend.coverprofile',workflow)
        self.assertIn('needs: [go-contract, android-companion-jvm]',workflow)
        self.assertIn('go test -race ./internal/config ./internal/control ./internal/daemon',workflow)
        self.assertNotIn('continue-on-error: true',workflow)

if __name__=='__main__':unittest.main(verbosity=2)
