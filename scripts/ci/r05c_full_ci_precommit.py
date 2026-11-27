#!/usr/bin/env python3
"""R05C HF04: execute *every* repository Python CI test, not a synthetic subset.

Run with `--run` from repository root; `--self-test` runs adversarial disposable
fixtures. No Gradle, root, adb, external network or persistent device access.
"""
from __future__ import annotations
import argparse
from pathlib import Path
import re
import subprocess
import sys
import tempfile

REQUIRED_TESTS = frozenset({
    'test_r03c_android_readonly_probe.py',
    'test_r03d_android_chronology.py',
    'test_r04_resolver.py',
    'test_r05a_hf01_sdk_setup.py',
    'test_r05a_source_contract.py',
    'test_r05b_source_contract.py',
    'test_r05c_hf01_emulator_ci.py',
    'test_r05c_hf02_sdkmanager_bootstrap.py',
    'test_r05c_hf03_sdk_inventory.py',
    'test_r05c_instrumentation_receipt.py',
    'test_r05c_source_contract.py',
})
PREFIX = 'XBS_R05C_HF04_FULL_PYTHON_CI'

class Refused(Exception):
    pass

def inventory(root:Path, minimal=None):
    ci = root/'scripts'/'ci'
    if not ci.is_dir() or ci.is_symlink():
        raise Refused('CI_DIRECTORY_MISSING_OR_SYMLINK')
    files = sorted(ci.glob('test_*.py'))
    names = {x.name for x in files}
    required = REQUIRED_TESTS if minimal is None else frozenset(minimal)
    if not required.issubset(names):
        raise Refused('REQUIRED_TEST_MISSING '+','.join(sorted(required-names)))
    if any(x.is_symlink() or not x.is_file() for x in files):
        raise Refused('CI_TEST_SYMLINK_OR_NONFILE')
    if len(files)!=len(names) or not files:
        raise Refused('CI_INVENTORY_AMBIGUOUS')
    return files

def select_failure(output):
    lines=output.splitlines()
    interesting=[]
    for line in lines:
        if re.search(r'(?i)^(FAIL|ERROR|FAILED|AssertionError|Traceback|Ran \d+ tests|[ \t]+File |[ \t]+self\.assert|[ \t]+raise |[^:]+: line \d+:)',line):
            interesting.append(line[:300])
    if not interesting:
        interesting=[line[:300] for line in lines[-18:]]
    return '\n'.join(interesting[-35:])[:4000]

def run_all(root:Path, required=None, timeout=100):
    tests=inventory(root, minimal=required)
    count=0
    for script in tests:
        try:
            proc=subprocess.run([sys.executable,str(script)],cwd=root,capture_output=True,
                                text=True,timeout=timeout,errors='replace',env=None)
        except subprocess.TimeoutExpired as exc:
            raise Refused('CI_TEST_TIMEOUT '+script.name) from exc
        output=proc.stdout+'\n'+proc.stderr
        matches=re.findall(r'(?m)^Ran (\d+) tests? in ',output)
        if proc.returncode or len(matches)!=1 or re.search(r'(?mi)^FAILED\s*\(|^FAILED$|^OK \(skipped=|\bskipped=\d+\b',output):
            raise Refused('CI_TEST_FAILED '+script.name+' rc='+str(proc.returncode)+' receipts='+str(matches)+'\n'+select_failure(output))
        ran=int(matches[0])
        if ran<=0:
            raise Refused('CI_TEST_ZERO_CASES '+script.name)
        count+=ran
        print(PREFIX+'_SCRIPT_PASS '+script.name+' cases='+str(ran),flush=True)
    print(PREFIX+'_ALL_TESTS_PASS files='+str(len(tests))+' cases='+str(count),flush=True)
    return len(tests),count

def self_test():
    with tempfile.TemporaryDirectory(prefix='xbs-r05c-hf04-runner-') as t:
        root=Path(t)
        ci=root/'scripts'/'ci'
        ci.mkdir(parents=True)
        # Mutation tests use two mandatory scripts, not the 11-file live inventory.
        names={'test_first.py','test_second.py'}
        good='import unittest\nclass T(unittest.TestCase):\n    def test_ok(self): self.assertTrue(True)\nif __name__=="__main__": unittest.main()\n'
        for name in names:(ci/name).write_text(good)
        assert run_all(root,required=names)==(2,2)
        (ci/'test_second.py').write_text('import unittest\nclass T(unittest.TestCase):\n    def test_fails(self): self.fail("injected failure")\nif __name__=="__main__": unittest.main()\n')
        try:run_all(root,required=names)
        except Refused as e:
            if 'CI_TEST_FAILED test_second.py' not in str(e):raise
        else:raise Refused('NEGATIVE_FAILED_CASE_ACCEPTED')
        (ci/'test_second.py').write_text('import unittest\nclass T(unittest.TestCase):\n    @unittest.skip("injected")\n    def test_skipped(self): pass\nif __name__=="__main__": unittest.main()\n')
        try:run_all(root,required=names)
        except Refused as e:
            if 'CI_TEST_FAILED test_second.py' not in str(e):raise
        else:raise Refused('NEGATIVE_SKIPPED_CASE_ACCEPTED')
        (ci/'test_second.py').write_text('print("phony green result")\n')
        try:run_all(root,required=names)
        except Refused as e:
            if 'CI_TEST_FAILED test_second.py' not in str(e):raise
        else:raise Refused('NEGATIVE_MISSING_RECEIPT_ACCEPTED')
        (ci/'test_second.py').unlink()
        try:inventory(root,minimal=names)
        except Refused as e:
            if 'REQUIRED_TEST_MISSING' not in str(e):raise
        else:raise Refused('NEGATIVE_MISSING_FILE_ACCEPTED')
        (ci/'test_second.py').write_text(good)
        (ci/'test_third.py').write_text(good)
        assert run_all(root,required=names)==(3,3)
        (ci/'test_third.py').unlink()
        (ci/'test_second.py').unlink()
        (ci/'test_second.py').symlink_to(ci/'test_first.py')
        try:inventory(root,minimal=names)
        except Refused as e:
            if 'CI_TEST_SYMLINK' not in str(e):raise
        else:raise Refused('NEGATIVE_SYMLINK_ACCEPTED')
    print(PREFIX+'_ADVERSARIAL_SELFTEST_PASS six guards',flush=True)

if __name__=='__main__':
    a=argparse.ArgumentParser()
    g=a.add_mutually_exclusive_group(required=True)
    g.add_argument('--self-test',action='store_true')
    g.add_argument('--run',action='store_true')
    args=a.parse_args()
    try:
        if args.self_test:self_test()
        else:run_all(Path.cwd())
    except Refused as e:
        print(PREFIX+'_REFUSED '+str(e),file=sys.stderr)
        sys.exit(1)
