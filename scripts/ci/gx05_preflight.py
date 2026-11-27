#!/usr/bin/env python3
"""GX05: strictly rootless hosted security/performance/physical-readiness evidence.

Pure library; does not execute local root, adb, Gradle, shell or device mutations.
Only an independently hosted emulator controller may supply measured inputs.
"""
from __future__ import annotations
import hashlib
import json
import math
import re
from pathlib import PurePosixPath

SCHEMA='xbs-gx05-hosted-preflight/v1'
HEX40=re.compile(r'[0-9a-f]{40}\Z')
HEX64=re.compile(r'[0-9a-f]{64}\Z')
SAFE_RUN=re.compile(r'[1-9][0-9]{0,18}\Z')
PREFIXES=('adb shell ', 'su ', 'sudo ', 'pm install ', 'magisk ', 'ksud ', 'rm -rf ', 'dd if=')
REDACT=re.compile(r'(?i)(bearer\s+\S+|(?:token|password|secret|authorization|api[_-]?key)\s*[:=]\s*\S+|/[Dd]ata/[Aa]db/[^\s]+|[A-Fa-f0-9]{64})')

class Refused(ValueError): pass

def require(test,reason):
    if not test: raise Refused('GX05_REFUSED '+reason)

def digest(raw):
    return hashlib.sha256(raw).hexdigest()

def redact(text):
    require(isinstance(text,str),'NON_TEXT_DIAGNOSTIC')
    return REDACT.sub('[REDACTED]',text.replace('\x00','?'))[:420]

def safe_relative(path):
    require(isinstance(path,str) and 1<=len(path)<=240,'UNSAFE_PATH')
    require(not path.startswith('/') and '\\' not in path and '\x00' not in path,'UNSAFE_PATH')
    parts=PurePosixPath(path).parts
    require(parts and all(p not in ('.','..','') for p in path.split('/')),'TRAVERSAL_PATH')
    require(not path.startswith('.git/') and not path.startswith('.devtool/'),'INTERNAL_PATH')
    return path

def actual_percentile(values,p):
    require(type(values) is list and len(values)>=7,'INSUFFICIENT_REAL_SAMPLES')
    require(all(type(v) is int and 0<v<120_000 for v in values),'INVALID_SAMPLE')
    require(p in (50,95),'UNSUPPORTED_PERCENTILE')
    ordered=sorted(values)
    return ordered[math.ceil((p/100)*len(ordered))-1]

def measured_android(record):
    """Require seven independent cold starts and bounded *measured* memory.

    A host-supplied assertion of pass is never trusted. All raw timings are
    used to derive p50/p95 with nearest-rank, and both budgets are enforced.
    """
    require(type(record) is dict,'MISSING_METRICS')
    require(set(record)=={'cold_launch_ms','pss_kib','sample_source','api','abi','emulator','physical_qualified','battery_savings_qualified'},'METRIC_SHAPE_DRIFT')
    require(record['sample_source']=='adb-am-start-W-TotalTime','NOT_OBSERVED_STARTUP')
    require(record['api']==36 and record['abi'] in ('x86_64','x86') and record['emulator'] is True,'NOT_DISPOSABLE_API36')
    require(record['physical_qualified'] is False and record['battery_savings_qualified'] is False,'FALSE_PHYSICAL_CLAIM')
    launches=record['cold_launch_ms']; mem=record['pss_kib']
    require(type(mem) is list and len(mem)==len(launches) and len(mem)>=7,'MISSING_MEMORY_SAMPLES')
    require(all(type(x) is int and 1<=x<=1_000_000 for x in mem),'UNTRUSTED_MEMORY_VALUES')
    p50=actual_percentile(launches,50);p95=actual_percentile(launches,95)
    max_pss=max(mem)
    require(p50<=8000 and p95<=15000,'STARTUP_BUDGET_EXCEEDED')
    require(max_pss<=524288,'MEMORY_BUDGET_EXCEEDED')
    return {'sample_count':len(launches),'startup_p50_ms':p50,'startup_p95_ms':p95,
            'max_pss_kib':max_pss,'budget_p50_ms':8000,'budget_p95_ms':15000,
            'budget_pss_kib':524288,'rootless_emulator_measurement':True,
            'battery_savings_qualified':False,'physical_qualified':False}

def verify_artifacts(items,*,source,run,attempt):
    require(type(items) is list and len(items)>=2,'REQUIRED_ARTIFACTS_MISSING')
    names=set();d=[]
    for x in items:
        require(type(x) is dict and set(x)=={'path','sha256','size','source_sha','run_id','run_attempt'},'ARTIFACT_RECEIPT_SHAPE')
        name=safe_relative(x['path']);require(name not in names,'DUPLICATE_ARTIFACT_PATH')
        names.add(name)
        require(type(x['size']) is int and 0<x['size']<=200_000_000,'ARTIFACT_SIZE_INVALID')
        require(isinstance(x['sha256'],str) and HEX64.fullmatch(x['sha256']),'INVALID_ARTIFACT_SHA')
        require(x['source_sha']==source and x['run_id']==run and x['run_attempt']==attempt,'ARTIFACT_SOURCE_ATTEMPT_DRIFT')
        d.append(name)
    return d

def evidence(*,identity,performance,artifacts,security,main_root=None):
    require(type(identity) is dict and set(identity)=={'source_sha','run_id','run_attempt','event','hosted'},'IDENTITY_SHAPE')
    sha,run,attempt=identity['source_sha'],identity['run_id'],identity['run_attempt']
    require(isinstance(sha,str) and HEX40.fullmatch(sha),'INVALID_SOURCE')
    require(isinstance(run,str) and SAFE_RUN.fullmatch(run) and isinstance(attempt,str) and SAFE_RUN.fullmatch(attempt),'INVALID_RUN_ATTEMPT')
    require(identity['hosted'] is True,'HOSTED_PR_REQUIRED')
    if identity['event']!='pull_request':
        require(main_root is not None and identity['event'] in ('push','workflow_dispatch'),'HOSTED_PR_REQUIRED')
        import os
        from gx06_stack_gate import validate_hosted_context, git
        require(identity['event']==os.environ.get('GITHUB_EVENT_NAME') and
                git(main_root,'rev-parse','HEAD')==sha,'MAIN_HOSTED_IDENTITY_DRIFT')
        require(validate_hosted_context(main_root,os.environ)==(run,attempt),'MAIN_HOSTED_RUN_DRIFT')
    require(type(security) is dict and set(security)=={'instrumented_count','failed','skipped','exported_components_verified','untrusted_receiver_tests'},'SECURITY_CASE_SHAPE')
    require(type(security['instrumented_count']) is int and security['instrumented_count']>=7,'ZERO_SECURITY_TESTS')
    require(security['failed']==0 and security['skipped']==0,'FAILED_OR_SKIPPED_SECURITY_TESTS')
    require(security['exported_components_verified'] is True and security['untrusted_receiver_tests'] is True,'SECURITY_ASSERTION_UNVERIFIED')
    m=measured_android(performance)
    names=verify_artifacts(artifacts,source=sha,run=run,attempt=attempt)
    return {'schema':SCHEMA,'source_sha':sha,'run_id':run,'run_attempt':attempt,
            'performance':m,'security_test_count':security['instrumented_count'],
            'verified_artifacts':sorted(names), 'original_cases_accepted':[],
            'physical_root_qualified':False,'signed_release_qualified':False,
            'polling_wakeups_measured':False,'battery_savings_qualified':False,
            'release_gate':'REFUSED_PENDING_PHYSICAL_AND_SIGNING'}

def physical_inventory(info):
    """Validate externally collected user-provided read-only metadata only."""
    require(type(info) is dict and set(info)=={'api','abi','root_manager','module_id','module_version','module_digest','device_is_disposable','user_consent'},'PHYSICAL_INVENTORY_SHAPE')
    require(type(info['api']) is int and 26<=info['api']<=50,'API_INVALID')
    require(info['abi'] in ('arm64-v8a','armeabi-v7a','x86_64'),'ABI_INVALID')
    require(info['root_manager'] in ('kernelsu','magisk','apatch','none','unknown'),'ROOT_MANAGER_INVALID')
    require(isinstance(info['module_id'],str) and re.fullmatch('[a-z0-9_.-]{1,80}',info['module_id']),'MODULE_ID_INVALID')
    require(isinstance(info['module_version'],str) and re.fullmatch('[A-Za-z0-9._+-]{1,80}',info['module_version']),'MODULE_VERSION_INVALID')
    require(isinstance(info['module_digest'],str) and HEX64.fullmatch(info['module_digest']),'MODULE_DIGEST_MISSING')
    require(info['user_consent'] is True and info['device_is_disposable'] is True,'EXPLICIT_DISPOSABLE_CONSENT_REQUIRED')
    return {'read_only_inventory_validated':True,'device_access_performed':False,
            'root_operations_performed':False,'physical_qualified':False,'release_qualified':False,
            'next':'Require separately approved R11 session for reversible device effects'}

def release_gate(*,security,performance,physical,signing,original_cases):
    # A green hosted job cannot substitute physical/signing/case evidence.
    require(type(original_cases) is list,'ORIGINAL_CASES_INVALID')
    return {'release_allowed':bool(security and performance and physical and signing and original_cases),
            'missing':[k for k,v in [('security',security),('performance',performance),('physical',physical),('signing',signing),('original_cases',bool(original_cases))] if not v]}

if __name__=='__main__':
    import argparse,sys
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--inspect-inventory',help='Read user-collected JSON; no adb/root/device calls')
    a=p.parse_args()
    if not a.inspect_inventory:
        print('GX05_HOSTED_LIBRARY_ONLY; use --inspect-inventory for an offline read-only physical manifest')
    else:
        from pathlib import Path
        try:
            f=Path(a.inspect_inventory)
            require(f.is_file() and not f.is_symlink() and f.stat().st_size<8192,'INVENTORY_FILE_INVALID')
            result=physical_inventory(json.loads(f.read_text(encoding='utf-8')))
            print(json.dumps(result,sort_keys=True))
        except (OSError,ValueError,TypeError) as exc:
            print(redact(str(exc)),file=sys.stderr);sys.exit(2)
