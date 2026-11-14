#!/usr/bin/env python3
"""R06B: execute source-pinned native Devtool plan/preflight/affected on hosted Linux.

No Gradle, ADB, device, root, GH dispatch, or production mutation. Exact SHA pin
is checked before installing a locally built Devtool wheel. Affected scenarios
operate only in disposable Git fixtures. No claimed native qualification from
static TOML inspection or from the self-test alone.
"""
from __future__ import annotations
import argparse
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import tomllib

REPO = 'Mikeyphw/xbsctl'
DEVTOOL_REPO = 'https://github.com/Mikeyphw/devtool.git'
DEVTOOL_SHA = '44eb17bbbd63194aa67cde0f7b63d5ada44fb96e'
DEVTOOL_VERSION = '1.1.0'
REQUIRED = {
    'source': ('job:xbs-go-mod-present', ()),
    'tests': ('job:xbs-go-test', ('source',)),
    'vet': ('job:xbs-go-vet', ('source',)),
    'contracts': ('job:xbs-contract-check', ('tests', 'vet')),
    'seal': ('job:xbs-final-seal', ('contracts',)),
    'module': ('job:xbs-build-module', ('seal',)),
}
UNSAFE = {'gradle', 'adb', 'su', 'ksud', 'magisk'}

class Refused(RuntimeError):
    pass

def need(condition: bool, message: str) -> None:
    if not condition:
        raise Refused(message)

def invoke(argv, *, cwd: Path, timeout: int = 120, env: dict | None = None) -> str:
    try:
        p = subprocess.run([str(x) for x in argv],cwd=cwd,capture_output=True,text=True,
                           errors='replace',timeout=timeout,env=env)
    except (OSError, subprocess.TimeoutExpired) as e:
        raise Refused('EXECUTION_FAILED '+str(argv[:3])+' '+type(e).__name__) from e
    if p.returncode:
        raise Refused('COMMAND_FAILED rc='+str(p.returncode)+' argv='+repr(argv[:5])+\
                      '\n'+(p.stdout+'\n'+p.stderr)[-3000:])
    return p.stdout

def parse_json(text: str, kind: str) -> dict:
    try:
        data = json.loads(text)
    except (TypeError, ValueError) as e:
        raise Refused('INVALID_'+kind+'_JSON') from e
    need(isinstance(data,dict),'NONOBJECT_'+kind)
    return data

def unwrap_native_response(raw: str, *, kind: str, command: str,
                           schema: int, action: str | None = None) -> tuple[dict,dict]:
    """Require the pinned upstream Devtool JSON envelope AND native response.

    Devtool 1.1.0 emits universal schema-1 envelope. Native plan/preflight are
    schema 2, while affected selection is schema 1. No envelope-only success.
    """
    outer=parse_json(raw,kind)
    native=outer.get('data')
    identity={
        'outer_schema':outer.get('schema_version'),
        'native_schema':native.get('schema_version') if isinstance(native,dict) else None,
        'outer_command':outer.get('command'),
        'native_action':native.get('action') if isinstance(native,dict) else None,
        'outer_ok':outer.get('ok'),
        'outer_exit_code':outer.get('exit_code'),
    }
    diagnostic=' '.join(k+'='+repr(v) for k,v in identity.items())
    prefix='DEVTOOL_JSON_CONTRACT_DRIFT kind='+kind+' '+diagnostic+' '
    need(type(outer.get('schema_version')) is int and outer['schema_version']==1,
         prefix+'OUTER_SCHEMA')
    need(outer.get('command')==command,prefix+'OUTER_COMMAND')
    need(outer.get('ok') is True and type(outer.get('exit_code')) is int and
         outer['exit_code']==0,prefix+'OUTER_SUCCESS')
    need(isinstance(native,dict),prefix+'NATIVE_DATA_MISSING')
    need(type(native.get('schema_version')) is int and native['schema_version']==schema,
         prefix+'NATIVE_SCHEMA')
    if action is not None:
        need(native.get('action')==action,prefix+'NATIVE_ACTION')
    return native,{
        'kind':kind,'outer_schema':1,'native_schema':schema,
        'outer_command':command,
        'native_action':native.get('action') if isinstance(native.get('action'),str) else None,
        'envelope_sha256':hashlib.sha256(raw.encode('utf-8')).hexdigest(),
    }

def check_hosted_source(repo:Path)->str:
    need(os.environ.get('GITHUB_ACTIONS')=='true' and sys.platform.startswith('linux'),
         'HOSTED_LINUX_GITHUB_ACTIONS_REQUIRED')
    need((repo/'.git').exists(),'REPO_GIT_MISSING')
    actual=git(repo,'rev-parse','HEAD')
    expected=os.environ.get('XBS_EXPECTED_HEAD_SHA','')
    need(re.fullmatch(r'[a-f0-9]{40}',expected) and expected==actual,
         'HOSTED_CHECKOUT_HEAD_SHA_MISMATCH')
    return actual

def count_go_events(output:str)->dict:
    """Measure real go test -json case outcomes; only two reviewed host skips.

    Neither skipped test is counted as passed. Every other test skip refuses,
    including changes in the reason or package, with safe bounded diagnostics.
    """
    allowed={
        ('github.com/Mikeyphw/xbsctl/internal/tuning',
         'TestRootIntegrationEffectFamilies'):
            'set XBS_ROOT_IT_ACK=effects-are-reversible',
        ('github.com/Mikeyphw/xbsctl/internal/control',
         'TestR02AuditedSpoolRejectsForeignOwnerWhenSupported'):
            'test requires ability to create a foreign-owned directory',
    }
    passed=set();skipped=set();packages=set();failed=[];details={}
    for index,line in enumerate(output.splitlines(),1):
        if not line.strip():continue
        try:row=json.loads(line)
        except ValueError as e:raise Refused('GO_TEST_JSON_INVALID_LINE_'+str(index)) from e
        need(isinstance(row,dict),'GO_TEST_JSON_NONOBJECT_LINE_'+str(index))
        event=row.get('Action');pkg=row.get('Package');test=row.get('Test')
        key=(pkg,test)
        if event=='output' and pkg and test:
            details.setdefault(key,[]).append(str(row.get('Output') or '')[:500])
        if event=='fail':failed.append(str(pkg or 'unknown')+'/'+str(test or 'package'))
        if event=='pass' and pkg and not test:packages.add(pkg)
        if event=='pass' and pkg and test:
            need(key not in passed and key not in skipped,'GO_TEST_DUPLICATE_OR_CONFLICTING_CASE '+str(key))
            passed.add(key)
        if event=='skip' and test:
            need(key not in skipped and key not in passed,'GO_TEST_DUPLICATE_OR_CONFLICTING_SKIP '+str(key))
            skipped.add(key)
    need(not failed,'GO_TEST_FAILURES '+repr(failed[:5]))
    approved=[]
    for key in sorted(skipped):
        reason=allowed.get(key)
        need(reason is not None,'GO_TEST_UNEXPECTED_SKIP package='+str(key[0])+' test='+str(key[1]))
        outputs=''.join(details.get(key,()))
        need(reason in outputs,'GO_TEST_SKIP_REASON_DRIFT package='+str(key[0])+
             ' test='+str(key[1])+' expected='+reason)
        approved.append({'package':key[0],'test':key[1],
                         'reason_contract':reason})
    need(len(approved)<=2,'GO_TEST_SKIP_BUDGET_EXCEEDED')
    need(bool(passed) and bool(packages),'GO_TEST_NO_ACTUAL_CASE_RECEIPTS')
    need({p for p,_ in passed}.issubset(packages),'GO_TEST_PACKAGE_NOT_PASSED')
    return {'test_cases':len(passed),'passed_packages':len(packages),
            'skipped_cases':len(approved),'approved_skips':approved,
            'unexpected_skipped_cases':0,'failed_cases':0}

def count_jvm_results(directory:Path)->dict:
    import xml.etree.ElementTree as ET
    need(directory.is_dir() and not directory.is_symlink(),'JVM_RESULTS_DIRECTORY_MISSING')
    files=sorted(directory.rglob('TEST-*.xml'))
    need(bool(files),'JVM_TEST_XML_MISSING')
    cases=set();failed=0;skipped=0
    for file in files:
        need(file.is_file() and not file.is_symlink(),'JVM_TEST_XML_UNSAFE')
        try:root=ET.parse(file).getroot()
        except (ET.ParseError,OSError) as e:raise Refused('JVM_TEST_XML_INVALID') from e
        need(root.tag in {'testsuite','testsuites'},'JVM_TEST_SUITE_INVALID')
        for case in root.iter('testcase'):
            key=(case.get('classname'),case.get('name'))
            need(all(key),'JVM_TEST_UNNAMED')
            need(key not in cases,'JVM_TEST_DUPLICATE_CASE')
            cases.add(key)
            if case.find('failure') is not None or case.find('error') is not None:failed+=1
            if case.find('skipped') is not None:skipped+=1
    need(not failed,'JVM_TEST_FAILURES '+str(failed))
    need(not skipped,'JVM_TEST_SKIPPED '+str(skipped))
    need(bool(cases),'JVM_TEST_ZERO_CASES')
    return {'test_cases':len(cases),'xml_files':len(files),
            'skipped_cases':0,'failed_cases':0}

def write_count_receipt(repo:Path,relative:str,sha:str,counts:dict)->None:
    output=repo/relative
    output.parent.mkdir(parents=True,exist_ok=True)
    need(not output.exists(),'STALE_COUNT_RECEIPT '+relative)
    payload={'schema':'xbs-r06b-hf03-test-counts/v1','xbs_source_sha':sha,
             'github_run_id':str(os.environ.get('GITHUB_RUN_ID') or ''),
             'github_run_attempt':str(os.environ.get('GITHUB_RUN_ATTEMPT') or ''),
             **counts}
    need(payload['github_run_id'].isdigit() and payload['github_run_attempt'].isdigit(),
         'GITHUB_RUN_IDENTITY_MISSING')
    output.write_text(json.dumps(payload,sort_keys=True,indent=2)+'\n')
    print('XBS_R06B_HF03_ACTUAL_CASE_COUNTS_PASS '+relative+
          ' cases='+str(counts['test_cases']),flush=True)

def count_go_hosted(repo:Path)->None:
    sha=check_hosted_source(repo)
    output=invoke(['go','test','-json','-count=1','./...'],cwd=repo,timeout=240)
    write_count_receipt(repo,'dist/r06b-go-test-counts.json',sha,count_go_events(output))

def select_jvm_result_variant(repo:Path)->dict:
    """Require actual Gradle JUnit XML from debug or release unit test task.

    The baseline-profile setup can make testReleaseUnitTest execute alongside
    testDebugUnitTest; do not assume only the debug result directory exists.
    """
    base=repo/'android-app/app/build/test-results'
    choices=(('debug',base/'testDebugUnitTest'),
             ('release',base/'testReleaseUnitTest'))
    observed=[]
    for variant,path in choices:
        need(not path.is_symlink(),'JVM_RESULTS_SYMLINK_VARIANT '+variant)
        if path.is_dir():
            files=sorted(path.rglob('TEST-*.xml'))
            if files:
                measured=count_jvm_results(path)
                observed.append((variant,measured))
    if not observed:
        state=' '.join(variant+'='+('directory-no-xml' if path.is_dir() else 'missing')
                       for variant,path in choices)
        raise Refused('JVM_NO_EXECUTED_JUNIT_CASES '+state+
                      ' checked=testDebugUnitTest,testReleaseUnitTest')
    selected=next(((v,c) for v,c in observed if v=='debug'),observed[0])
    variant,counts=selected
    result={**counts,'selected_variant':variant,
            'available_variants':[v for v,_ in observed]}
    need(result['test_cases']>0,'JVM_SELECTED_VARIANT_ZERO_CASES')
    return result

def count_jvm_hosted(repo:Path)->None:
    sha=check_hosted_source(repo)
    result=select_jvm_result_variant(repo)
    print('XBS_R06B_HF04_JVM_XML_VARIANT '+result['selected_variant']+
          ' available='+','.join(result['available_variants']),flush=True)
    write_count_receipt(repo,'android-evidence/r06b-jvm-test-counts.json',sha,result)

def validate_manifest(text: str) -> None:
    cfg = tomllib.loads(text)
    go = cfg['targets']['xbs']; android = cfg['targets']['xbs_android']
    need(go['runner']=='go' and go['root']=='.','NATIVE_GO_TARGET_MISSING')
    need(go.get('affected_ownership')=='inputs-only','GO_ROOT_SWALLOWS_ALL_AFFECTED_PATHS')
    need({'cmd/**','internal/**','module/**','go.mod','.devtool.toml'}.issubset(set(go['inputs'])),
         'GO_AFFECTED_INPUTS_MISSING')
    need(android['root']=='android-app' and '../cmd/**' in android['inputs'] and
         '../internal/**' in android['inputs'],'ANDROID_SHARED_GO_INPUTS_MISSING')
    steps=go['workflows']['ci-module']; actual={}
    for node in steps:
        nid=node['id']; need(nid not in actual,'DUPLICATE_GRAPH_NODE')
        actual[nid]=(node['ref'],tuple(node['depends_on']))
    need(actual==REQUIRED,'MODULE_GRAPH_WEAKENED')
    for k,act in [('xbs-go-test','test'),('xbs-go-vet','lint')]:
        job=cfg['jobs'][k]
        need(job['runner']=='go' and job['action']==act and 'command' not in job,
             'GO_ACTION_NOT_NATIVE_'+k)
    need(android['artifacts']['required'] and android['artifacts']['verify'],
         'ANDROID_ARTIFACT_VERIFICATION_WEAKENED')

def validate_plan(plan: dict) -> dict:
    need(plan.get('schema_version')==2 and plan.get('action')=='ci-module',
         'DEVTOOL_PLAN_SCHEMA_OR_ACTION_DRIFT')
    need(plan.get('targets')==['xbs'],'PLAN_TARGET_DRIFT')
    graph=plan.get('graph'); need(isinstance(graph,dict),'PLAN_GRAPH_MISSING')
    nodes=graph.get('nodes');need(isinstance(nodes,list) and len(nodes)==len(REQUIRED),
        'DEVTOOL_GRAPH_NODE_COUNT_DRIFT')
    by_id={};keys={}
    for node in nodes:
        need(isinstance(node,dict) and node.get('id') not in by_id and node.get('id'),
             'DUPLICATE_NODE_ID')
        by_id[node['id']]=node; keys[node['id']]=node.get('key')
    need(set(by_id)==set(REQUIRED),'DEVTOOL_GRAPH_NODES_DRIFT')
    for nid,(ref,deps) in REQUIRED.items():
        node=by_id[nid]
        need(node.get('ref')==ref and node.get('depends_on')==[keys[d] for d in deps],
             'DEVTOOL_DAG_EDGE_DRIFT_'+nid)
    ordered=graph.get('order');need(isinstance(ordered,list) and set(ordered)==set(keys.values()),
          'DEVTOOL_PLAN_ORDER_INCOMPLETE')
    for nid,(_,deps) in REQUIRED.items():
        for dep in deps:
            need(ordered.index(keys[dep])<ordered.index(keys[nid]),'DEVTOOL_NODE_ORDER_INVALID')
    commands=plan.get('commands');need(isinstance(commands,list) and len(commands)>=6,
                                    'DEVTOOL_PLAN_COMMANDS_MISSING')
    refs={x.get('workflow_ref') for x in commands if isinstance(x,dict)}
    need({v[0] for v in REQUIRED.values()}.issubset(refs),'DEVTOOL_NATIVE_COMMAND_EXPANSION_MISSING')
    for x in commands:
        need(isinstance(x,dict) and isinstance(x.get('argv'),list),'MISSING_COMMAND_ARGV')
        argv=x['argv'];joined=' '.join(map(str,argv)).lower()
        need(not any(re.search(r'(?<![\w-])'+bad+r'(?![\w-])',joined) for bad in UNSAFE),
             'LOCAL_ANDROID_ROOT_COMMAND_IN_GO_PLAN')
    for ref,expected in [('job:xbs-go-test',['go','test','-json']),
                         ('job:xbs-go-vet',['go','vet']),
                         ('job:xbs-final-seal',['go','run','./cmd/xbsrepo','final-seal']),
                         ('job:xbs-build-module',['go','run','./cmd/xbsrepo','build-module'])]:
        cmd=[x for x in commands if x.get('workflow_ref')==ref]
        need(len(cmd)==1,'PLAN_COMMAND_MISSING_OR_DUPLICATE_'+ref)
        argv=list(map(str,cmd[0]['argv']))
        need(argv[:len(expected)]==expected or (ref=='job:xbs-go-test' and argv[:3]==expected),
             'COMMAND_NOT_NATIVE_OR_CHANGED_'+ref)
    return {'graph_nodes':len(nodes),'commands':len(commands),
            'source_bound_module_gate':True}

def validate_preflight(data:dict)->dict:
    need(data.get('healthy') is True,'NATIVE_DEVTOOL_PREFLIGHT_UNHEALTHY')
    need('xbs' in (data.get('targets') or []),'PREFLIGHT_MISSING_GO_TARGET')
    need('go' in (data.get('runners') or []),'PREFLIGHT_GO_RUNNER_ABSENT')
    for check in data.get('checks') or []:
        need(check.get('status') not in {'missing','unsafe','error','unsupported','incompatible'},
             'PREFLIGHT_UNSAFE_CHECK')
    return {'healthy':True,'runners':data['runners']}

def validate_affected(data: dict, *, changed: str, expected: set[str], require_uncertain=False)->dict:
    need(data.get('schema_version')==1,'AFFECTED_SCHEMA_CHANGED')
    actual=data.get('targets');need(isinstance(actual,list) and set(actual)==expected,
                                'AFFECTED_TARGET_SELECTION_WRONG '+str(actual)+' expected '+str(expected))
    need(changed in (data.get('changed_paths') or []),'AFFECTED_CHANGED_PATH_NOT_IDENTIFIED')
    if require_uncertain:
        need(changed in (data.get('uncertain_paths') or []),'UNKNOWN_PATH_NOT_CONSERVATIVE')
    return {'changed':changed,'targets':sorted(actual),'unknown_fallback':bool(require_uncertain)}

def git(root: Path,*args)->str:
    return invoke(['git','-C',str(root),*args],cwd=root).strip()

def fixture(root:Path,manifest:str,changed:str)->tuple[str,str]:
    root.mkdir(parents=True)
    invoke(['git','init','-q'],cwd=root)
    git(root,'config','user.name','XBS R06B fixture')
    git(root,'config','user.email','xbs-r06b@example.invalid')
    inputs={'.devtool.toml':manifest,'go.mod':'module example.invalid/r06b\n\ngo 1.23\n',
            'cmd/xbs/main.go':'package main\nfunc main(){}\n',
            'android-app/.keep':'', 'README.md':'baseline\n'}
    for name,value in inputs.items():
        f=root/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(value)
    git(root,'add','.');git(root,'commit','-qm','baseline')
    base=git(root,'rev-parse','HEAD')
    path=root/changed;path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text('fixture update\n')
    git(root,'add','--',changed);git(root,'commit','-qm','source change')
    return base,git(root,'rev-parse','HEAD')

def run_native(py:Path,root:Path,manifest:str,tmp:Path)->dict:
    exe=[str(py),'-m','devtool','--repo',str(root)]
    raw=invoke([*exe,'plan','ci-module','--target','xbs','--json'],cwd=root,timeout=150)
    plan,plan_env=unwrap_native_response(raw,kind='PLAN',command='plan',
                                          schema=2,action='ci-module')
    info=validate_plan(plan)
    raw=invoke([*exe,'preflight','--target','xbs','--workflow','ci-module','--json'],
               cwd=root,timeout=150)
    pre,pre_env=unwrap_native_response(raw,kind='PREFLIGHT',command='preflight',schema=2)
    info.update(validate_preflight(pre))
    explanation=invoke([*exe,'plan','ci-module','--target','xbs','--explain'],cwd=root,timeout=150)
    need('module' in explanation.lower() and 'seal' in explanation.lower(),
         'HUMAN_PLAN_MISSING_DEPENDENCIES')
    info['plan_explain_sha256']=hashlib.sha256(explanation.encode()).hexdigest()
    affected=[];envelopes=[plan_env,pre_env]
    for scenario,(changed,wanted,unknown) in enumerate([
        ('cmd/r06b-fixture.go',{'xbs','xbs_android'},False),
        ('android-app/app/r06b-fixture.txt',{'xbs_android'},False),
        ('docs/r06b-unknown.txt',{'xbs','xbs_android'},True),
    ]):
        dest=tmp/('affected-'+str(scenario))
        base,head=fixture(dest,manifest,changed)
        raw=invoke([str(py),'-m','devtool','--repo',str(dest),'affected',
                    '--base',base,'--head',head,'--json'],cwd=dest,timeout=100)
        data,evidence=unwrap_native_response(raw,kind='AFFECTED',command='affected',schema=1)
        affected.append(validate_affected(data,changed=changed,expected=wanted,
                                          require_uncertain=unknown))
        envelopes.append(evidence)
    info['affected']=affected
    info['native_json_envelopes']=envelopes
    return info

def verify_provenance(py:Path,wheel:Path,devtool_source:Path,expected_repo:Path)->dict:
    sha=git(devtool_source,'rev-parse','HEAD')
    need(sha==DEVTOOL_SHA,'DEVTOOL_SOURCE_SHA_NOT_PINNED')
    sha256=hashlib.sha256(wheel.read_bytes()).hexdigest()
    output=invoke([str(py),'-c',
       'import devtool,importlib.metadata,json,sys; print(json.dumps({"file":devtool.__file__,"version":devtool.__version__,"distribution":importlib.metadata.version("devtool"),"python":sys.executable}))'],
       cwd=expected_repo)
    v=parse_json(output,'DEVTOOL_IDENTITY')
    need(v.get('version')==v.get('distribution')==DEVTOOL_VERSION,
         'DEVTOOL_WHEEL_VERSION_MISMATCH')
    need(str(v.get('file','')).startswith(str(py.parent.parent)),'DEVTOOL_IMPORTED_UNTRUSTED_GLOBAL')
    return {'devtool_source_commit':sha,'installed_devtool_version':v['version'],
            'wheel_sha256':sha256,'installed_module_location':v['file']}

def hosted(repo:Path)->None:
    actual=check_hosted_source(repo)
    counts_file=repo/'dist/r06b-go-test-counts.json'
    need(counts_file.is_file() and not counts_file.is_symlink(),
         'GO_CASE_COUNT_RECEIPT_MISSING')
    counts_raw=counts_file.read_bytes()
    counts=parse_json(counts_raw.decode('utf-8'),'GO_COUNTS')
    need(counts.get('schema')=='xbs-r06b-hf03-test-counts/v1' and
         counts.get('xbs_source_sha')==actual and
         counts.get('github_run_id')==str(os.environ.get('GITHUB_RUN_ID') or '') and
         counts.get('github_run_attempt')==str(os.environ.get('GITHUB_RUN_ATTEMPT') or '') and
         type(counts.get('test_cases')) is int and counts['test_cases']>0 and
         type(counts.get('passed_packages')) is int and counts['passed_packages']>0 and
         counts.get('failed_cases')==0 and counts.get('unexpected_skipped_cases')==0 and
         type(counts.get('skipped_cases')) is int and 0<=counts['skipped_cases']<=2 and
         isinstance(counts.get('approved_skips'),list) and
         counts['skipped_cases']==len(counts['approved_skips']),
         'GO_CASE_COUNT_RECEIPT_UNQUALIFIED')
    manifest=(repo/'.devtool.toml').read_text()
    validate_manifest(manifest)
    with tempfile.TemporaryDirectory(prefix='xbs-r06b-devtool-',dir=os.environ.get('RUNNER_TEMP') or None) as folder:
        temp=Path(folder)
        source=temp/'source';source.mkdir()
        git(source,'init','-q')
        git(source,'remote','add','origin',DEVTOOL_REPO)
        git(source,'fetch','--no-tags','--depth','1','origin',DEVTOOL_SHA)
        git(source,'checkout','--detach','FETCH_HEAD')
        need(git(source,'rev-parse','HEAD')==DEVTOOL_SHA,'DEVTOOL_FETCHED_SHA_WRONG')
        venv=temp/'venv'
        invoke([sys.executable,'-m','venv',str(venv)],cwd=repo,timeout=180)
        py=venv/'bin'/'python'
        pip=[str(py),'-m','pip','--disable-pip-version-check','--no-input']
        invoke([*pip,'install','rich==14.2.0','PyYAML==6.0.3',
                'setuptools==80.9.0','wheel==0.45.1'],cwd=repo,timeout=360)
        wheels=temp/'wheels';wheels.mkdir()
        invoke([*pip,'wheel','--no-deps','--no-build-isolation','--no-index','--wheel-dir',str(wheels),
                str(source)],cwd=repo,timeout=360)
        candidates=list(wheels.glob('devtool-*.whl'))
        need(len(candidates)==1 and candidates[0].is_file(),'EXPECTED_ONE_PINNED_DEVTOOL_WHEEL')
        wheel=candidates[0]
        invoke([*pip,'install','--no-index','--no-deps',str(wheel)],cwd=repo,timeout=180)
        provenance=verify_provenance(py,wheel,source,repo)
        report=run_native(py,repo,manifest,temp)
    receipt={'schema':'xbs-r06b-devtool-runtime/v1','xbs_source_sha':actual,
             'github_run_id':str(os.environ.get('GITHUB_RUN_ID') or ''),
             'github_run_attempt':str(os.environ.get('GITHUB_RUN_ATTEMPT') or ''),
             **provenance,'native':report,
             'go_test_counts':{'cases':counts['test_cases'],'packages':counts['passed_packages'],
                               'sha256':hashlib.sha256(counts_raw).hexdigest(),
                               'approved_skips':counts['skipped_cases']},
             'android_gradle_ran':False,
             'device_mutated':False,'physical_root_qualified':False,'release_qualified':False}
    output=repo/'dist/r06b-devtool-qualification.json'
    output.parent.mkdir(parents=True,exist_ok=True)
    need(not output.exists(),'STALE_R06B_RECEIPT_EXISTS')
    output.write_text(json.dumps(receipt,sort_keys=True,indent=2)+'\n')
    print('XBS_R06B_PINNED_WHEEL_SHA256 '+provenance['wheel_sha256'],flush=True)
    print('XBS_R06B_ACTUAL_NATIVE_PLAN_AND_PREFLIGHT_PASS nodes='+str(report['graph_nodes'])+
          ' affected_cases='+str(len(report['affected'])),flush=True)
    print('XBS_R06B_RECEIPT '+str(output),flush=True)

def main():
    a=argparse.ArgumentParser()
    a.add_argument('mode',choices=['hosted','validate','count-go','count-jvm'])
    a.add_argument('--repo',type=Path,default=Path.cwd())
    v=a.parse_args()
    try:
        if v.mode=='hosted':hosted(v.repo.resolve())
        elif v.mode=='count-go':count_go_hosted(v.repo.resolve())
        elif v.mode=='count-jvm':count_jvm_hosted(v.repo.resolve())
        else:
            validate_manifest((v.repo/'.devtool.toml').read_text())
            print('XBS_R06B_MANIFEST_POLICY_PASS')
    except (Refused,OSError,KeyError,ValueError,subprocess.SubprocessError) as exc:
        print('XBS_R06B_REFUSED '+str(exc),file=sys.stderr,flush=True)
        return 2
    return 0

if __name__=='__main__':raise SystemExit(main())
