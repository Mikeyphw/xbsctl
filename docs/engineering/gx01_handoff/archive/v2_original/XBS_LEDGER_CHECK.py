#!/usr/bin/env python3
"""Read-only XBS roadmap ledger consistency and no-fake-PASS guard.

Usage: python3 XBS_LEDGER_CHECK.py [implementation_ledger.json] [test_matrix.json]
No network, no device calls, no repository modifications, no background jobs.
"""
from __future__ import annotations
import json
from pathlib import Path
import re
import sys

SHA = re.compile(r"[0-9a-f]{40}\Z")
IMPLEMENTATION = frozenset(("NOT_STARTED", "IN_PROGRESS", "IMPLEMENTED_UNVERIFIED",
                            "HOSTED_VERIFIED", "EMULATOR_VERIFIED", "PHYSICAL_VERIFIED",
                            "ACCEPTED", "BLOCKED"))
TEST = frozenset(("NOT_RUN", "RUNNING", "PASS_AT_EXACT_SHA", "FAIL",
                  "SKIPPED_NOT_QUALIFIED", "BLOCKED"))


def validate(ledger: dict, matrix: dict) -> list[str]:
    issues = []
    if ledger.get('schema_version') != 'xbs-consolidated-ledger/v2':
        issues.append('unknown ledger schema')
    if matrix.get('schema_version') != 'xbs-test-matrix/v2':
        issues.append('unknown test matrix schema')
    if ledger.get('baseline') != matrix.get('baseline'):
        issues.append('ledger/test baseline mismatch')
    phases = ledger.get('phases', [])
    tests = matrix.get('cases', [])
    findings = ledger.get('findings', [])
    ps = {p.get('id'): p for p in phases}
    ts = {t.get('id'): t for t in tests}
    if len(ps) != len(phases): issues.append('duplicate phase id')
    if len(ts) != len(tests): issues.append('duplicate test id')
    task_ids = [x.get('id') for p in phases for x in p.get('tasks', [])]
    if len(task_ids) != len(set(task_ids)): issues.append('duplicate implementation task id')
    if len(findings) != len({f.get('id') for f in findings}): issues.append('duplicate finding ID')
    present = set()
    remaining = set(ps)
    while remaining:
        ready = {p for p in remaining if set(ps[p].get('depends_on', [])) <= present}
        if not ready:
            issues.append('phase dependency cycle or missing prerequisite')
            break
        present |= ready
        remaining -= ready
    for p in phases:
        pid = p.get('id')
        if p.get('status') not in IMPLEMENTATION:
            issues.append(f'{pid}: invalid implementation status')
        if not p.get('gate_id') or not p.get('gate'):
            issues.append(f'{pid}: missing phase gate')
        references = p.get('test_ids', [])
        if not references or not set(references) <= set(ts):
            issues.append(f'{pid}: missing test coverage or unknown test ID')
        if not p.get('tasks'):
            issues.append(f'{pid}: empty task inventory')
        if p.get('status') == 'ACCEPTED':
            if any(x.get('status') != 'ACCEPTED' for x in p.get('tasks', [])):
                issues.append(f'{pid}: accepted with incomplete tasks')
            if any(ts[x].get('status') != 'PASS_AT_EXACT_SHA' for x in references if x in ts):
                issues.append(f'{pid}: accepted with failed/unrun/absent test')
            if not p.get('evidence'):
                issues.append(f'{pid}: accepted without source-bound gate evidence')
        for task in p.get('tasks', []):
            tid = task.get('id')
            if not str(tid).startswith(str(pid)+'-'):
                issues.append(f'{tid}: does not belong to phase {pid}')
            if task.get('status') not in IMPLEMENTATION:
                issues.append(f'{tid}: invalid status')
            if not task.get('acceptance') or len(task['acceptance']) < 20:
                issues.append(f'{tid}: missing substantive acceptance')
            if task.get('status') in ('IMPLEMENTED_UNVERIFIED', 'HOSTED_VERIFIED',
                                       'EMULATOR_VERIFIED', 'PHYSICAL_VERIFIED', 'ACCEPTED'):
                if not SHA.fullmatch(str(task.get('implementation_sha') or '')):
                    issues.append(f'{tid}: changed status without exact implementation SHA')
                if not task.get('evidence'):
                    issues.append(f'{tid}: changed status without evidence')
    for t in tests:
        tid = t.get('id')
        if t.get('status') not in TEST:
            issues.append(f'{tid}: invalid test status')
        if not t.get('phases') or not set(t['phases']) <= set(ps):
            issues.append(f'{tid}: orphan or unknown phase')
        if len(t.get('required_assertion', '')) < 20:
            issues.append(f'{tid}: no meaningful assertion')
        if t.get('status') == 'PASS_AT_EXACT_SHA':
            if not SHA.fullmatch(str(t.get('source_sha') or '')):
                issues.append(f'{tid}: PASS without exact source SHA')
            if not t.get('evidence'):
                issues.append(f'{tid}: PASS without execution evidence')
            if (t.get('observed_test_count') or 0) < 1:
                issues.append(f'{tid}: zero-test PASS')
            if (t.get('observed_assertions') or 0) < 1:
                issues.append(f'{tid}: no observed assertion')
            if t.get('lane') == 'ANDROID_PHYSICAL_OPT_IN':
                if not t.get('session_id'):
                    issues.append(f'{tid}: physical PASS without authorized session')
            elif not isinstance(t.get('run_id'), int) or t['run_id'] < 1 or not isinstance(t.get('attempt'), int) or t['attempt'] < 1:
                issues.append(f'{tid}: hosted PASS without run id/attempt')
    for f in findings:
        if f.get('primary_phase') not in ps:
            issues.append(f"{f.get('id')}: no mapped phase")
        if f.get('resolution') == 'CLOSED':
            if not f.get('closing_evidence') or ps.get(f.get('primary_phase'), {}).get('status') != 'ACCEPTED':
                issues.append(f"{f.get('id')}: closed without accepted phase evidence")
    code = {f.get('id') for f in findings if str(f.get('id')).startswith('CODE-')}
    if code != {f'CODE-{i:02}' for i in range(1, 18)}:
        issues.append('expected CODE-01 through CODE-17 coverage missing')
    return issues


def main() -> int:
    base = Path(__file__).resolve().parent
    args = sys.argv[1:]
    if len(args) > 2:
        print('usage: python3 XBS_LEDGER_CHECK.py [ledger.json] [test_matrix.json]', file=sys.stderr)
        return 2
    source = Path(args[0]) if args else base/'XBS_IMPLEMENTATION_LEDGER.json'
    matrix = Path(args[1]) if len(args)>1 else base/'XBS_TEST_MATRIX.json'
    try:
        l = json.loads(source.read_text(encoding='utf-8'))
        t = json.loads(matrix.read_text(encoding='utf-8'))
        errors = validate(l,t)
    except (OSError, ValueError, TypeError) as e:
        print('XBS_LEDGER_INVALID:', str(e), file=sys.stderr)
        return 1
    if errors:
        for e in errors: print('XBS_LEDGER_REFUSED: '+e, file=sys.stderr)
        return 1
    print('XBS_LEDGER_SCHEMA_AND_TRACEABILITY_VALID')
    print('XBS_LEDGER_QUALIFICATION_NOT_CLAIMED')
    print(f"XBS_LEDGER_COUNTS phases={len(l['phases'])} tasks={sum(len(p['tasks']) for p in l['phases'])} tests={len(t['cases'])} findings={len(l['findings'])}")
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
