#!/usr/bin/env python3
"""R09C fail-closed executable event-parser and workflow regressions (no Go/Gradle)."""
from __future__ import annotations
import importlib.util
import json
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]
SOURCE=Path(__file__).with_name('r09c_backend_model.py')
SPEC=importlib.util.spec_from_file_location('xbs_r09c_matrix',SOURCE)
M=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(M)
PKG='github.com/Mikeyphw/xbsctl/internal/'

def events(count=1):
    rows=[]
    for name,names in M.PACKAGE_TESTS.items():
        for test in sorted(names):
            for i in range(count):rows.extend(({'Action':'run','Package':PKG+name,'Test':test},{'Action':'pass','Package':PKG+name,'Test':test}))
        rows.append({'Action':'pass','Package':PKG+name})
    return '\n'.join(map(json.dumps,rows))+'\n'

class BackendFaultMatrixTest(unittest.TestCase):
    def test_real_package_inventory_and_named_case_counts(self):
        got=M.parse_test_events(events());self.assertEqual(got['required_root_tests'],9)
        self.assertEqual(got['passed_root_test_events'],9)
        self.assertEqual(M.parse_test_events(events(3),repetitions=3)['passed_root_test_events'],27)
    def test_missing_one_test_refuses(self):
        row=events().splitlines();target='TestR09CFakeAndroidEffectRoundTrip'
        corrupted='\n'.join(x for x in row if target not in x)
        with self.assertRaises(M.Refused):M.parse_test_events(corrupted)
    def test_unexpected_unknown_test_refuses(self):
        mutant=events()+json.dumps({'Action':'pass','Package':PKG+'tuning','Test':'TestInjectedExtra'})
        with self.assertRaises(M.Refused):M.parse_test_events(mutant)
    def test_skip_fail_and_invalid_json_refuse(self):
        for action in ('skip','fail'):
            with self.subTest(action=action),self.assertRaises(M.Refused):
                M.parse_test_events(events()+json.dumps({'Action':action,'Package':PKG+'tuning','Test':'TestR09CFakeAndroidDryRunIsPure'}))
        with self.assertRaises(M.Refused):M.parse_test_events(events()+'broken JSON\n')
    def test_empty_or_wrong_package_refuses(self):
        with self.assertRaises(M.Refused):M.parse_test_events('')
        with self.assertRaises(M.Refused):M.parse_test_events(events().replace(PKG+'tuning',PKG+'daemon'))
    def test_repetition_must_be_real_not_one_pass(self):
        with self.assertRaises(M.Refused):M.parse_test_events(events(),repetitions=3)
    def test_receipt_id_attempt_and_root_negative(self):
        sha='a'*40
        b=M.parse_test_events(events())
        b.update({'coverage_scope':'r09c_selected_tests_only','coverage_total_statements_pct':2.5,'coverage_profile_sha256':'a'*64,'schema':M.SCHEMA,'xbs_source_sha':sha,'github_run_id':'99','github_run_attempt':'2',
                  'repeat_passed_root_events':27,'race_passed_root_events':9,'race_packages':2,
                  'fault_domains':sorted(M.PACKAGE_TESTS),'root_executed':False,'physical_root_qualified':False,
                  'device_mutated':False,'release_qualified':False})
        self.assertEqual(M.validate_receipt(b,sha,99,2)['tests'],9)
        for patch in [{'xbs_source_sha':'b'*40},{'github_run_attempt':'1'},
            {'passed_root_test_events':8},{'race_passed_root_events':0},
            {'repeat_passed_root_events':9},{'required_test_ids':[]},
            {'physical_root_qualified':True},{'fault_domains':[]}]:
            with self.subTest(patch=patch),self.assertRaises(M.Refused):M.validate_receipt({**b,**patch},sha,99,2)
    def test_real_go_fault_sources_and_nonroot_scope(self):
        sources={'tuning':ROOT/'internal/tuning/r09c_simulated_runtime_test.go','cli':ROOT/'internal/cli/r09c_golden_test.go'}
        for name,path in sources.items():
            self.assertTrue(path.is_file(),name)
            data=path.read_text()
            self.assertIn('package '+name,data)
            for test in M.PACKAGE_TESTS[name]:
                self.assertEqual(data.count('func '+test+'('),1,(name,test))
            for forbidden in ('exec.Command(', 'SystemRunner{}', 'ksud module install', 'su -c ', 'adb shell'):
                self.assertNotIn(forbidden,data,(name,forbidden))
        self.assertIn('r09cAndroid',sources['tuning'].read_text())
        self.assertIn('r09cCLI',sources['cli'].read_text())

    def test_real_workflow_requires_matrix_and_existing_gates(self):
        path=ROOT/'.github/workflows/devtool-xbs-ci.yml'
        text=path.read_text() if path.exists() else ''
        self.assertIn('R09C fake Android backend and CLI golden contracts',text)
        self.assertIn('r09c_backend_model.py --hosted',text)
        self.assertIn('dist/r09c-backend-model.json',text)
        self.assertIn('dist/r09c-backend.coverprofile',text)
        self.assertIn('R09A bounded backend fuzz, repeat and coverage',text)
        self.assertIn('needs: [go-contract, android-companion-jvm]',text)
        self.assertIn('connectedDebugAndroidTest',text)
        self.assertIn('go test -race ./internal/config ./internal/control ./internal/daemon',text)
if __name__=='__main__':unittest.main(verbosity=2)
