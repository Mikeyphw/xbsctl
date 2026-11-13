#!/usr/bin/env python3
"""Fail closed if API36 hosted instrumentation did not execute every required case.
Never packages raw device data or logs; prints only aggregate counts and fixed class names.
"""
from __future__ import annotations
import argparse
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

EXPECTED_CLASSES = {
 'R05cBridgeReplayInstrumentedTest': 4,
 'R05cTaskerBoundaryInstrumentedTest': 4,
 'R05cLifecycleInstrumentedTest': 3,
 'R05cUiSmokeInstrumentedTest': 1,
 'PostAuditPendingCommandInstrumentedTest': 3,
 'PostAuditRoomMainThreadInstrumentedTest': 1,
}

class Refused(RuntimeError): pass

def verify(directory:Path, expected=None):
    if not directory.is_dir(): raise Refused('R05C_INSTRUMENTATION_RESULTS_DIR_MISSING')
    names = {k: set() for k in (expected or EXPECTED_CLASSES)}
    duplicates=set()
    xml_files=sorted(directory.rglob('*.xml'))
    if not xml_files: raise Refused('R05C_INSTRUMENTATION_XML_MISSING')
    failures=0;skipped=0;unknown=0
    for filename in xml_files:
        try:root=ET.parse(filename).getroot()
        except (ET.ParseError,OSError) as err:raise Refused('R05C_INSTRUMENTATION_XML_INVALID '+filename.name) from err
        if root.tag not in ('testsuites','testsuite'):
            continue
        suites = [root] if root.tag=='testsuite' else list(root.iter('testsuite'))
        for suite in suites:
            for case in suite.findall('testcase'):
                test_name=case.get('name','')
                class_name=case.get('classname','').rsplit('.',1)[-1]
                if class_name not in names: continue
                if not test_name: raise Refused('R05C_INSTRUMENTATION_CASE_UNNAMED')
                if case.find('failure') is not None or case.find('error') is not None:failures+=1
                if case.find('skipped') is not None:skipped+=1
                key=(class_name,test_name)
                if key in duplicates:raise Refused('R05C_INSTRUMENTATION_DUPLICATE_CASE')
                if test_name in names[class_name]:
                    duplicates.add(key)
                    raise Refused('R05C_INSTRUMENTATION_DUPLICATE_CASE')
                names[class_name].add(test_name)
    if failures:raise Refused('R05C_INSTRUMENTATION_FAILURES '+str(failures))
    if skipped:raise Refused('R05C_INSTRUMENTATION_SKIPPED '+str(skipped))
    for name,minimum in (expected or EXPECTED_CLASSES).items():
        if len(names[name]) < minimum:
            raise Refused('R05C_INSTRUMENTATION_MISSING_CASES '+name+' got='+str(len(names[name]))+' minimum='+str(minimum))
    total=sum(map(len,names.values()))
    if total<16:raise Refused('R05C_INSTRUMENTATION_TOO_FEW_CASES '+str(total))
    print('XBS_R05C_API36_INSTRUMENTATION_CASES_PASS '+str(total))
    for name in sorted(names):print('XBS_R05C_CLASS_PASS '+name+' count='+str(len(names[name])))
    return total

if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--results',type=Path,required=True)
    args=p.parse_args()
    try:verify(args.results)
    except Refused as err:
        print('XBS_R05C_REFUSED: '+str(err),file=sys.stderr);sys.exit(1)
