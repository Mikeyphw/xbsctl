#!/usr/bin/env python3
"""GX03 hosted-only rootless emulator orchestration and real evidence capture.

No local execution: requires GitHub hosted, an exact source SHA, a unique emulator
serial, ro.kernel.qemu=1, the correct SDK, and x86 ABI. All Gradle work belongs
exclusively to GitHub Actions. Prints direct subprocess stdout/stderr for fails.
"""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from gx03_evidence import (Refused,need,sha,PKG,COMPAT,JOURNEYS,STAGE_A,STAGE_B,
                           validate_identity,junit_receipt,artifact_apk_pair,redact_png)

ROOT=Path(__file__).resolve().parents[2]
RESULTS=ROOT/'android-app/app/build/outputs/androidTest-results/connected'
OUT=ROOT/'android-evidence/gx03'

def run(argv,*,timeout=180,check=True,bytes_mode=False,env=None):
    need(all(isinstance(s,str) and s and '\x00' not in s for s in argv),'COMMAND_ARGUMENT_INVALID')
    try:
        p=subprocess.run(argv,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.PIPE,
                         timeout=timeout,env=env,text=not bytes_mode)
    except (OSError,subprocess.TimeoutExpired) as e:
        raise Refused('HOSTED_COMMAND_INTERRUPTED '+repr(argv[:3])+' '+type(e).__name__) from e
    if check and p.returncode:
        error=p.stderr.decode(errors='replace') if bytes_mode else p.stderr
        output=p.stdout.decode(errors='replace') if bytes_mode else p.stdout
        raise Refused('COMMAND_FAILED rc='+str(p.returncode)+' '+repr(argv[:4])+'\n'+(output+'\n'+error)[-5500:])
    return p

def adb(serial,*args,timeout=90,check=True,bytes_mode=False):
    return run(['adb','-s',serial,*args],timeout=timeout,check=check,bytes_mode=bytes_mode)

def host_identity(api):
    need(os.getenv('GITHUB_ACTIONS')=='true' and os.getenv('GITHUB_EVENT_NAME')=='pull_request',
         'HOSTED_PULL_REQUEST_ONLY')
    expected=os.environ.get('XBS_EXPECTED_HEAD_SHA','')
    head=run(['git','rev-parse','HEAD']).stdout.strip()
    need(head==expected,'CHECKOUT_SOURCE_SHA_MISMATCH')
    serials=[]
    for line in run(['adb','devices']).stdout.splitlines()[1:]:
        cols=line.split()
        if len(cols)>=2 and cols[1]=='device':serials.append(cols[0])
    need(len(serials)==1,'EXACTLY_ONE_EMULATOR_REQUIRED observed='+repr(serials))
    serial=serials[0]
    qemu=adb(serial,'shell','getprop','ro.kernel.qemu').stdout.strip()
    sdk=adb(serial,'shell','getprop','ro.build.version.sdk').stdout.strip()
    abi=adb(serial,'shell','getprop','ro.product.cpu.abi').stdout.strip()
    need(sdk==str(api),'EMULATOR_API_MISMATCH expected='+str(api)+' actual='+sdk)
    receipt=validate_identity(head,os.getenv('GITHUB_RUN_ID',''),os.getenv('GITHUB_RUN_ATTEMPT',''),api,serial,qemu,abi)
    need((ROOT/'android-app/app/build.gradle').is_file(),'APP_GRADLE_MISSING')
    return serial,receipt

def gradle_test(class_and_method):
    need(re.fullmatch(r'io\.github\.mikeceead\.xbscompanion\.[A-Za-z0-9_]+(?:#[A-Za-z0-9_]+)?',class_and_method),
         'INVALID_INSTRUMENTATION_SELECTOR')
    args=['gradle','-p','android-app','--offline','--no-daemon','--console=plain',
          '-PxbsR05cEmulator=true',
          '-Pandroid.testInstrumentationRunnerArguments.clearPackageData=false',
          '-Pandroid.testInstrumentationRunnerArguments.class='+class_and_method,
          ':app:connectedDebugAndroidTest']
    print('GX03_HOSTED_GRADLE '+args[-1]+' selector='+class_and_method,flush=True)
    p=subprocess.run(args,cwd=ROOT,timeout=1100,check=False)
    need(p.returncode==0,'GX03_GRADLE_INSTRUMENTATION_FAILED rc='+str(p.returncode))

def pair(serial,identity,apksigner,aapt):
    subprocess.run(['gradle','-p','android-app','--offline','--no-daemon','--console=plain',
                    '-PxbsR05cEmulator=true',':app:assembleDebug',':app:assembleDebugAndroidTest'],
                   cwd=ROOT,timeout=750,check=True)
    base=ROOT/'android-app/app/build/outputs/apk'
    results=artifact_apk_pair({'app':base/'debug/app-debug.apk',
                               'test':base/'androidTest/debug/app-debug-androidTest.apk'},
                              apksigner,aapt,lambda argv:run(argv))
    out=OUT/('api'+str(identity['api_level']))
    out.mkdir(parents=True,exist_ok=True)
    paths={'app':base/'debug/app-debug.apk','test':base/'androidTest/debug/app-debug-androidTest.apk'}
    for kind,file in paths.items():
        dst=out/(kind+'-debug.apk')
        shutil.copyfile(file,dst)
        need(sha(dst.read_bytes())==results[kind]['sha256'],'STAGED_APK_SHA256_DRIFT '+kind)
    return results

def screenshot(serial,width,theme,out):
    need(width in (360,393) and theme in ('light','dark'),'SCREENSHOT_REQUEST_INVALID')
    pixels=width*3
    height=2400 if width==360 else 2620
    adb(serial,'shell','wm','size',str(pixels)+'x'+str(height))
    adb(serial,'shell','wm','density','480')
    adb(serial,'shell','cmd','uimode','night','yes' if theme=='dark' else 'no')
    adb(serial,'shell','am','force-stop',PKG)
    adb(serial,'shell','am','start','-W','-n',PKG+'/.MainActivity')
    raw=adb(serial,'exec-out','screencap','-p',bytes_mode=True).stdout
    redacted,meta=redact_png(raw,pixels)
    target=out/('gx03-'+str(width)+'dp-'+theme+'-redacted.png')
    target.write_bytes(redacted)
    need(target.is_file() and sha(target.read_bytes())==meta['redacted_sha256'],
         'REDATED_SCREENSHOT_HASH_MISMATCH')
    meta.update({'file':target.name,'theme_requested':theme,'screen_context':'offline-clean-emulator',
                 'locale_rtl_accepted':False,'accessibility_accepted':False,
                 'redaction_scope':'status_and_navigation_bands_only'})
    return meta

def process_death(serial):
    cls='io.github.mikeceead.xbscompanion.Gx03ProcessDeathInstrumentedTest'
    gradle_test(cls+'#stageAWriteDurablePendingOwnership')
    first=junit_receipt(RESULTS,STAGE_A)
    adb(serial,'shell','am','start','-W','-n',PKG+'/.MainActivity')
    pid=adb(serial,'shell','pidof',PKG,check=False)
    need(pid.returncode==0 and pid.stdout.strip().isdigit(),'PROCESS_DEATH_TARGET_NOT_ALIVE_BEFORE_KILL')
    oldpid=pid.stdout.strip()
    adb(serial,'shell','am','force-stop',PKG)
    pid_after=adb(serial,'shell','pidof',PKG,check=False)
    need(pid_after.returncode!=0 or not pid_after.stdout.strip(),
         'FORCE_STOP_DID_NOT_TERMINATE_APP')
    gradle_test(cls+'#stageBInspectRecoveryAfterExternalForceStop')
    second=junit_receipt(RESULTS,STAGE_B)
    return {'stage_a':first,'stage_b':second,'host_force_stop_executed':True,
            'old_process_pid_observed':True,'post_kill_absent':True,
            'root_command_effects_executed':False,'outcome':'real-process-death-tested',
            'claimed_no_automatic_replay':False}

def execute(args):
    serial,identity=host_identity(args.api)
    if args.mode=='preflight':
        # Before first instrumentation launch: never attach to a physical or preinstalled device.
        old=adb(serial,'shell','pm','path',PKG,check=False)
        need('package:' not in old.stdout,'EMULATOR_NOT_FRESH_APP_PREINSTALLED')
        print('GX03_FRESH_EMULATOR_PREFLIGHT_PASS api='+str(args.api)+' serial='+serial,flush=True)
        return
    home=Path(os.getenv('ANDROID_HOME') or os.getenv('ANDROID_SDK_ROOT') or '')
    need(home.is_dir(),'ANDROID_SDK_HOME_MISSING')
    apksigner=home/'build-tools/36.0.0/apksigner'
    aapt=home/'build-tools/36.0.0/aapt'
    need(apksigner.is_file() and aapt.is_file(),'PINNED_APK_TOOLS_MISSING')
    report={**identity,'schema':'xbs-gx03-android-evidence/v1'}
    if args.mode=='api36':
        need(args.api==36,'AUTHORITATIVE_API36_REQUIRED')
        gradle_test('io.github.mikeceead.xbscompanion.Gx03JourneyInstrumentedTest')
        report['journey_assertions']=junit_receipt(RESULTS,JOURNEYS)
        report['process_death']=process_death(serial)
        out=OUT/'api36';out.mkdir(parents=True,exist_ok=True)
        captures=[]
        try:
            for width in (360,393):
                for theme in ('light','dark'):
                    captures.append(screenshot(serial,width,theme,out))
        finally:
            # Changes were made only to a disposable emulator; restore global settings.
            adb(serial,'shell','wm','size','reset',check=False)
            adb(serial,'shell','wm','density','reset',check=False)
            adb(serial,'shell','cmd','uimode','night','auto',check=False)
        report['screenshots']=captures
        report['journey_scope']='MainActivity five tabs, hostile navigation, real external process death'
        report['original_cases_accepted']=[]
        report['remaining_cases']='Editor CRUD, locale/RTL, accessibility and other R10 cases remain unmapped until separate exact tests'
    elif args.mode=='compat':
        need(args.api in COMPAT,'COMPAT_API_MUST_BE_26_OR_34')
        report['compat_assertions']=junit_receipt(RESULTS,COMPAT[args.api])
        report['original_cases_accepted']=[]
        report['compat_scope']='emulator smoke/Tasker boundary only; no full API-specific signoff'
    else:raise Refused('UNKNOWN_GX03_MODE')
    report['apk_pair']=pair(serial,identity,str(apksigner),str(aapt))
    out=OUT/('api'+str(args.api));out.mkdir(parents=True,exist_ok=True)
    dest=out/'evidence.json'
    need(not dest.exists(),'STALE_ANDROID_RECEIPT_REFUSED')
    dest.write_text(json.dumps(report,indent=2,sort_keys=True)+'\n')
    need(json.loads(dest.read_text())==report,'ANDROID_RECEIPT_WRITE_MISMATCH')
    print('GX03_HOSTED_ANDROID_EVIDENCE_PASS api='+str(args.api)+' source='+identity['source_sha']+
          ' run='+identity['github_run_id']+' attempt='+identity['github_run_attempt']+
          ' physical_root_qualified=false original_cases_accepted=0',flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--mode',required=True,choices=['preflight','api36','compat'])
    parser.add_argument('--api',required=True,type=int,choices=[26,34,36])
    args=parser.parse_args()
    try:execute(args)
    except (Refused,ValueError,KeyError,OSError,subprocess.TimeoutExpired,subprocess.CalledProcessError) as e:
        print('GX03_HOSTED_REFUSED '+str(e),file=sys.stderr,flush=True)
        sys.exit(2)
