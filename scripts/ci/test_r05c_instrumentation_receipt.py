#!/usr/bin/env python3
"""Prove that missing, skipped, partial and failed emulator reports are refused."""
from pathlib import Path
import importlib.util
import tempfile
import unittest
import xml.etree.ElementTree as ET

MODULE=Path(__file__).with_name('r05c_verify_instrumentation.py')
spec=importlib.util.spec_from_file_location('r05c_verify',MODULE)
verify=importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)

class Receipts(unittest.TestCase):
    def make(self, root, specs, bad=None, skipped=None):
        suite=ET.Element('testsuite')
        for cls,count in specs.items():
            for i in range(count):
                case=ET.SubElement(suite,'testcase',name='test'+str(i),classname='io.github.mikeceead.xbscompanion.'+cls)
                if bad==(cls,i):ET.SubElement(case,'failure',message='injected')
                if skipped==(cls,i):ET.SubElement(case,'skipped')
        ET.ElementTree(suite).write(root/'TEST-r05c.xml')

    def test_complete_case_inventory_passes(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);self.make(p,verify.EXPECTED_CLASSES)
            self.assertEqual(verify.verify(p),16)

    def test_missing_class_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);self.make(p,{k:v for k,v in verify.EXPECTED_CLASSES.items() if not k.startswith('R05cUi')})
            with self.assertRaises(verify.Refused):verify.verify(p)

    def test_skipped_case_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);self.make(p,verify.EXPECTED_CLASSES,skipped=('R05cUiSmokeInstrumentedTest',0))
            with self.assertRaises(verify.Refused):verify.verify(p)

    def test_failure_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);self.make(p,verify.EXPECTED_CLASSES,bad=('R05cUiSmokeInstrumentedTest',0))
            with self.assertRaises(verify.Refused):verify.verify(p)

    def test_empty_or_malformed_xml_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)
            with self.assertRaises(verify.Refused):verify.verify(p)
            (p/'TEST-broken.xml').write_text('<notxml')
            with self.assertRaises(verify.Refused):verify.verify(p)

if __name__=='__main__':unittest.main(verbosity=2)
