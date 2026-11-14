#!/usr/bin/env python3
"""R06B HF04: exercise actual Go event and Gradle JUnit case-count contracts.

No repository mutation, physical device, root, or local Gradle usage.
"""
from __future__ import annotations
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

HERE=Path(__file__).resolve().parent
SPEC=importlib.util.spec_from_file_location('hf04_runtime',HERE/'r06b_devtool_qualification.py')
assert SPEC and SPEC.loader
D=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(D)
ROOT=HERE.parents[1]

ROOT_SKIP=('github.com/Mikeyphw/xbsctl/internal/tuning',
           'TestRootIntegrationEffectFamilies',
           'set XBS_ROOT_IT_ACK=effects-are-reversible')
OWNER_SKIP=('github.com/Mikeyphw/xbsctl/internal/control',
            'TestR02AuditedSpoolRejectsForeignOwnerWhenSupported',
            'test requires ability to create a foreign-owned directory')

def event(action,package='example.invalid/good',test=None,output=None):
    data={'Action':action,'Package':package}
    if test is not None:data['Test']=test
    if output is not None:data['Output']=output
    return json.dumps(data)

def stream(skips=()):
    rows=[event('run',test='TestBasic'),event('pass',test='TestBasic'),
          event('pass')]
    for pkg,test,why in skips:
        rows.extend((event('run',pkg,test),
                     event('output',pkg,test,'    integration_test.go:1: '+why+'\n'),
                     event('skip',pkg,test),event('pass',pkg)))
    return '\n'.join(rows)

class HF04ActualCountContracts(unittest.TestCase):
    def test_actual_pass_count_and_no_skips(self):
        c=D.count_go_events(stream())
        self.assertEqual(c['test_cases'],1)
        self.assertEqual(c['skipped_cases'],0)
    def test_named_host_skips_are_counted_not_passed(self):
        c=D.count_go_events(stream((ROOT_SKIP,OWNER_SKIP)))
        self.assertEqual(c['test_cases'],1)
        self.assertEqual(c['skipped_cases'],2)
        self.assertEqual(c['unexpected_skipped_cases'],0)
        self.assertEqual({s['test'] for s in c['approved_skips']},
                         {ROOT_SKIP[1],OWNER_SKIP[1]})
    def test_unrelated_skip_refused(self):
        with self.assertRaisesRegex(D.Refused,'GO_TEST_UNEXPECTED_SKIP'):
            D.count_go_events(stream((('example.invalid/other','TestBad','unsupported'),)))
    def test_named_skip_wrong_package_refused(self):
        with self.assertRaisesRegex(D.Refused,'GO_TEST_UNEXPECTED_SKIP'):
            D.count_go_events(stream((('example.invalid/other',ROOT_SKIP[1],ROOT_SKIP[2]),)))
    def test_named_skip_changed_reason_refused(self):
        with self.assertRaisesRegex(D.Refused,'GO_TEST_SKIP_REASON_DRIFT'):
            D.count_go_events(stream(((ROOT_SKIP[0],ROOT_SKIP[1],'some other failure'),)))
    def test_skip_without_explanation_refused(self):
        with self.assertRaisesRegex(D.Refused,'GO_TEST_SKIP_REASON_DRIFT'):
            D.count_go_events('\n'.join((stream(),event('skip',ROOT_SKIP[0],ROOT_SKIP[1]))))
    def test_conflicting_pass_and_skip_refused(self):
        x=stream((ROOT_SKIP,))+'\n'+event('pass',ROOT_SKIP[0],ROOT_SKIP[1])
        with self.assertRaisesRegex(D.Refused,'GO_TEST_DUPLICATE_OR_CONFLICTING_CASE'):
            D.count_go_events(x)
    def test_failing_go_case_refused(self):
        with self.assertRaisesRegex(D.Refused,'GO_TEST_FAILURES'):
            D.count_go_events(stream()+'\n'+event('fail','example.invalid/good','TestBad'))
    def test_duplicate_pass_refused(self):
        with self.assertRaisesRegex(D.Refused,'GO_TEST_DUPLICATE_OR_CONFLICTING_CASE'):
            D.count_go_events(stream()+'\n'+event('pass','example.invalid/good','TestBasic'))
    def test_invalid_go_json_refused(self):
        with self.assertRaisesRegex(D.Refused,'GO_TEST_JSON_INVALID_LINE'):
            D.count_go_events(stream()+'\nnot json')
    def test_no_passed_go_case_refused(self):
        with self.assertRaisesRegex(D.Refused,'GO_TEST_NO_ACTUAL_CASE_RECEIPTS'):
            D.count_go_events(event('pass'))

    @staticmethod
    def xml(root,variant='release',skipped=False,failed=False):
        path=root/'android-app/app/build/test-results'/('test'+variant.capitalize()+'UnitTest')
        path.mkdir(parents=True,exist_ok=True)
        suite=ET.Element('testsuite')
        case=ET.SubElement(suite,'testcase',classname='XbsJvmTest',name='tested')
        if skipped:ET.SubElement(case,'skipped')
        if failed:ET.SubElement(case,'failure')
        ET.ElementTree(suite).write(path/'TEST-XbsJvmTest.xml')
        return path
    def test_release_variant_selected_from_real_xml(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);self.xml(root)
            c=D.select_jvm_result_variant(root)
            self.assertEqual((c['selected_variant'],c['test_cases']),('release',1))
    def test_debug_variant_preferred_when_both_have_xml(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);self.xml(root,'debug');self.xml(root,'release')
            c=D.select_jvm_result_variant(root)
            self.assertEqual(c['selected_variant'],'debug')
            self.assertEqual(c['available_variants'],['debug','release'])
    def test_missing_real_xml_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(D.Refused,'JVM_NO_EXECUTED_JUNIT_CASES'):
                D.select_jvm_result_variant(Path(folder))
    def test_present_empty_debug_directory_does_not_forge_test_counts(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            (root/'android-app/app/build/test-results/testDebugUnitTest').mkdir(parents=True)
            with self.assertRaisesRegex(D.Refused,'JVM_NO_EXECUTED_JUNIT_CASES'):
                D.select_jvm_result_variant(root)
    def test_skipped_release_jvm_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);self.xml(root,skipped=True)
            with self.assertRaisesRegex(D.Refused,'JVM_TEST_SKIPPED'):
                D.select_jvm_result_variant(root)
    def test_failed_release_jvm_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);self.xml(root,failed=True)
            with self.assertRaisesRegex(D.Refused,'JVM_TEST_FAILURES'):
                D.select_jvm_result_variant(root)
    def test_release_unit_test_source_and_hosted_gates(self):
        app=(ROOT/'android-app/app/build.gradle').read_text()
        self.assertIn("testBuildType (project.hasProperty('xbsR05cEmulator') ? 'debug' : 'release')",app)
        workflow=(ROOT/'.github/workflows/devtool-xbs-ci.yml').read_text()
        self.assertIn(':app:testDebugUnitTest',workflow)
        self.assertIn('needs: [go-contract, android-companion-jvm]',workflow)
        self.assertIn('connectedDebugAndroidTest',workflow)
        self.assertIn('r05c_verify_instrumentation.py --results',workflow)

if __name__=='__main__':unittest.main(verbosity=2)
