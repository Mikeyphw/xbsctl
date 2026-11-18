#!/usr/bin/env python3
"""R07A canonical GitHub CI gate policy (source-only, fail closed).

No third-party YAML parser dependency. This checks a deliberately restricted,
reviewed subset of the canonical workflow; YAML syntax is separately parsed by
GitHub upon PR. The analyzer refuses unrecognised actions and duplicate jobs.
"""
from __future__ import annotations
import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import os
import sys

REPO = Path(__file__).resolve().parents[2]
WORKFLOW = '.github/workflows/devtool-xbs-ci.yml'
GX03_COMPAT = '.github/workflows/gx03-hosted-compat.yml'
GX03_COMPAT_SHA256 = '03f0b8810d61ee2b1c1051382e87d1c2e3ce215736f105f319afbe65ac973b15'
S7_P05 = '.github/workflows/devtool-s7-p05-android-consumer.yml'
S7_P05_GIT_BLOB = '11367960a6fd311926db890b632433000b767a0b'
# GitHub REST git/ref/tags/vN, recursively dereferenced to commit on 2026-11-14.
ACTIONS = {
    'actions/checkout': '11d5960a326750d5838078e36cf38b85af677262',
    'actions/setup-go': '40f1582b2485089dde7abd97c1529aa768e1baff',
    'actions/setup-java': 'cf277c60eb25467037889841efdb72551f06f6c3',
    'actions/upload-artifact': 'ea165f8d65b6e75b540449e92b4886f43607fa02',
    'android-actions/setup-android': '9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407',
    'gradle/actions/setup-gradle': 'ed408507eac070d1f99cc633dbcf757c94c7933a',
    'reactivecircus/android-emulator-runner': 'a421e43855164a8197daf9d8d40fe71c6996bb0d',
}
REQUIRED = {
    'go-contract': (
        'R07A immutable action and mandatory gate audit',
        'Go unit tests','Go race detector','Go vet',
        'R06B HF03 actual Go test case counts',
        'R06B source-pinned Devtool runtime qualification',
        'XBS contracts','Source-bound final seal','Android module candidate',
        'Module checksum',
    ),
    'android-companion-jvm': (
        'R05C HF04 complete repository Python CI suite',
        'Android JVM unit suite','R06B HF03 actual Android JVM case counts',
        'Publish SHA-bound Android JVM counts',
    ),
    'android-companion-api36': (
        'R05C source and workflow contracts','Enable KVM for API36 emulator',
        'Install Android API36 build-tools 36.0.0',
        'Android API36 rootless instrumentation',
        'Publish SHA-bound debug candidate, no logs or private device state',
    ),
}
OPTIONAL = 'android-companion-debug'
HEAD_EXPR = 'ref: ${{ github.event.pull_request.head.sha || github.sha }}'

class Refused(RuntimeError):
    pass

def require(value,why):
    if not value: raise Refused(why)

def sections(text):
    """Return direct jobs ONLY inside the top-level ``jobs:`` mapping.

    It is critical not to count ``on.push`` or ``on.pull_request`` as jobs.
    The source workflow is a deliberately constrained YAML shape: one top-level
    jobs mapping, job names at exactly two spaces, and steps nested deeper.
    Reject duplicated/missing jobs rather than guessing from arbitrary YAML.
    """
    anchors=list(re.finditer(r'(?m)^jobs:[ \t]*(?:#.*)?$',text))
    require(len(anchors)==1,'JOBS_SECTION_MISSING_OR_DUPLICATE')
    start=anchors[0].end()
    # A later top-level mapping ends the job inventory; not event triggers.
    tail=text[start:]
    end=re.search(r'(?m)^[A-Za-z][A-Za-z0-9_-]*:[ \t]*(?:#.*)?$',tail)
    scope=tail[:end.start()] if end else tail
    hits=list(re.finditer(r'(?m)^  ([a-z][a-z0-9-]*):[ \t]*(?:#.*)?$',scope))
    require(bool(hits),'JOBS_SECTION_NO_JOBS')
    names=[x.group(1) for x in hits]
    require(len(names)==len(set(names)),'DUPLICATE_JOB_IDS')
    return {m.group(1):scope[m.end():hits[i+1].start() if i+1<len(hits) else len(scope)]
            for i,m in enumerate(hits)}

def guard(source):
    require(isinstance(source,str) and bool(source.strip()),'WORKFLOW_EMPTY')
    require('on:\n' in source and '  pull_request:\n' in source,'PR_TRIGGER_MISSING')
    require('permissions:\n  contents: read\n' in source,'LEAST_PRIVILEGE_MISSING')
    require(re.search(r'(?m)^jobs:\s*$',source),'JOBS_MISSING')
    require(not re.search(r'(?m)^\s*continue-on-error\s*:',source),'CONTINUE_ON_ERROR')
    require('|| true' not in source,'FAIL_OPEN_SHELL')
    require(not re.search(r'(?m)^\s*(?:-\s*)?uses:\s*[^\s]+@(?:v\d+|main|master)\s*$',source),'MUTABLE_ACTION_REFERENCE')
    actual = re.findall(r'(?m)^\s*(?:-\s*)?uses:\s*([^\s#]+)\s*$',source)
    require(bool(actual),'NO_ACTIONS')
    for item in actual:
        require('@' in item,'ACTION_UNPINNED')
        name,sha=item.rsplit('@',1)
        require(re.fullmatch(r'[a-f0-9]{40}',sha),'ACTION_NOT_40HEX '+name)
        require(name in ACTIONS,'UNREVIEWED_ACTION '+name)
        require(sha==ACTIONS[name],'ACTION_SHA_DRIFT '+name)
    blocks=sections(source)
    require(set(blocks)==set(REQUIRED)|{OPTIONAL},'JOB_INVENTORY_DRIFT '+','.join(sorted(blocks)))
    require(source.count(HEAD_EXPR)==4,'CHECKOUT_SHA_BOUNDARY_MISSING')
    require(len(actual)>=7,'ACTION_STEP_LOST')
    for name,requirements in REQUIRED.items():
        block=blocks[name]
        require('runs-on: ubuntu-latest' in block,'HOSTED_RUNNER_MISSING '+name)
        require(not re.search(r'(?m)^\s+if:\s*',block),'CONDITIONAL_REQUIRED_GATE '+name)
        require(not re.search(r'(?m)^\s+continue-on-error\s*:',block),'OPTIONAL_REQUIRED_GATE '+name)
        for label in requirements:
            require(block.count('      - name: '+label+'\n')==1,'REQUIRED_STEP_MISSING '+name+' '+label)
        require(block.count(HEAD_EXPR)==1,'JOB_HEAD_PIN_MISSING '+name)
    api=blocks['android-companion-api36']
    require('needs: [go-contract, android-companion-jvm]' in api,'API36_DEPENDENCIES_WEAKENED')
    require('api-level: 36' in api and 'arch: x86_64' in api,'API36_EMULATOR_DRIFT')
    require('connectedDebugAndroidTest' in api and 'r05c_verify_instrumentation.py --results' in api,'REAL_INSTRUMENTATION_REMOVED')
    require('if-no-files-found: error' in api,'API36_ARTIFACT_FAIL_OPEN')
    require('SOURCE_SHA' in api and 'SHA256SUMS' in api,'API36_PROVENANCE_MISSING')
    go=blocks['go-contract']
    require('go test -race ./internal/config ./internal/control ./internal/daemon' in go,'RACE_GATE_MISSING')
    require('dist/r06b-go-test-counts.json' in go and 'dist/r06b-devtool-qualification.json' in go,'GO_RECEIPT_MISSING')
    require('if-no-files-found: error' in go,'MODULE_ARTIFACT_FAIL_OPEN')
    jvm=blocks['android-companion-jvm']
    require('android-evidence/r06b-jvm-test-counts.json' in jvm,'JVM_RECEIPT_MISSING')
    require('if-no-files-found: error' in jvm,'JVM_ARTIFACT_FAIL_OPEN')
    require('r05c_full_ci_precommit.py --run' in jvm,'ALL_PYTHON_GATE_MISSING')
    require('gradle --no-daemon --console=plain :app:testDebugUnitTest' in jvm,'JVM_TEST_TASK_MISSING')
    require('if: github.event_name == \'workflow_dispatch\' && inputs.android_debug == true' in blocks[OPTIONAL],
            'OPTIONAL_DEBUG_SEMANTICS_DRIFT')
    return {'required_jobs':len(REQUIRED),'action_invocations':len(actual),
            'action_dependencies':len(ACTIONS),'checkout_head_pins':4}

def audit(root:Path, *, hosted=False):
    workflow=root/WORKFLOW
    require(workflow.is_file() and not workflow.is_symlink(),'CANONICAL_WORKFLOW_MISSING')
    # GX03 and S7 P05 are the only additional reviewed workflows. S7 is
    # accepted ONLY with its exact Git blob identity from the independently
    # merged main commit; never use filename-only trust or a mutable tag as proof.
    files=list((root/'.github/workflows').glob('*.yml'))+list((root/'.github/workflows').glob('*.yaml'))
    original={WORKFLOW,GX03_COMPAT}
    actual={str(p.relative_to(root)) for p in files}
    reviewed=original|({S7_P05} if S7_P05 in actual else set())
    require(len(files)==len(actual) and actual==reviewed and
            all(p.is_file() and not p.is_symlink() for p in files),
            'UNREVIEWED_EXTRA_WORKFLOW')
    compat=root/GX03_COMPAT
    require(hashlib.sha256(compat.read_bytes()).hexdigest()==GX03_COMPAT_SHA256,
            'GX03_COMPAT_WORKFLOW_SHA256_DRIFT')
    if S7_P05 in actual:
        data=(root/S7_P05).read_bytes()
        require(len(data)<16384 and
                hashlib.sha1(b'blob '+str(len(data)).encode('ascii')+bytes([0])+data).hexdigest()==S7_P05_GIT_BLOB,
                'S7_P05_WORKFLOW_GIT_BLOB_DRIFT')
    result=guard(workflow.read_text(encoding='utf-8'))
    result['reviewed_workflows']=len(reviewed)
    if hosted:
        require(os.getenv('GITHUB_ACTIONS')=='true','GITHUB_ACTIONS_REQUIRED')
        sha=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
        expected=os.getenv('XBS_EXPECTED_HEAD_SHA','')
        require(bool(re.fullmatch(r'[a-f0-9]{40}',expected)) and sha==expected,'GITHUB_HEAD_SHA_MISMATCH')
    return result

if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--repo',type=Path,default=REPO)
    p.add_argument('--hosted',action='store_true')
    a=p.parse_args()
    try:
        result=audit(a.repo.resolve(),hosted=a.hosted)
        print('XBS_R07A_CI_POLICY_PASS '+ ' '.join(k+'='+str(v) for k,v in result.items()),flush=True)
    except Refused as e:
        print('XBS_R07A_REFUSED '+str(e),file=sys.stderr)
        raise SystemExit(2)
