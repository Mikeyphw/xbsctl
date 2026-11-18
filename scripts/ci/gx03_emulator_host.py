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

def verified_direct_stage_receipt(output:str,selector:str)->dict:
    """Accept precisely one successful, real AndroidJUnitRunner instrumentation.

    Android instrumentation may return shell exit 0 even when assertions fail.
    Both per-test status codes and final runner status are mandatory.
    """
    need(isinstance(output,str) and 0<len(output)<256_000,'DIRECT_STAGE_OUTPUT_UNSAFE')
    need(re.fullmatch(r'io\.github\.mikeceead\.xbscompanion\.Gx03ProcessDeathInstrumentedTest#[A-Za-z0-9_]+',selector),
         'DIRECT_STAGE_SELECTOR_INVALID')
    clazz,method=selector.split('#')
    lines=[v.strip() for v in output.splitlines()]
    codes=[v.split(':',1)[1].strip() for v in lines
           if v.startswith('INSTRUMENTATION_STATUS_CODE:')]
    need(codes==['1','0'],'DIRECT_STAGE_STATUS_CODES_INVALID '+repr(codes))
    need(sum(v=='INSTRUMENTATION_STATUS: class='+clazz for v in lines)>=1,
         'DIRECT_STAGE_CLASS_MISSING')
    need(sum(v=='INSTRUMENTATION_STATUS: test='+method for v in lines)>=1,
         'DIRECT_STAGE_METHOD_MISSING')
    need('INSTRUMENTATION_STATUS: numtests=1' in lines,'DIRECT_STAGE_COUNT_MISSING')
    need(lines.count('INSTRUMENTATION_CODE: -1')==1,
         'DIRECT_STAGE_FINAL_RESULT_NOT_PASS')
    need(any(re.fullmatch(r'OK \(1 test\)',v) for v in lines),
         'DIRECT_STAGE_ONE_TEST_PASS_RECEIPT_MISSING')
    need(not any('FAILURES!!!' in v or 'INSTRUMENTATION_FAILED' in v or
                 'INSTRUMENTATION_ABORTED' in v or 'shortMsg=' in v or
                 v.startswith('FAILURES:') for v in lines),
         'DIRECT_STAGE_FAILURE_MARKER')
    return {'required_testcases':1,'total_testcases':1,'failed_cases':0,
            'skipped_cases':0,'testcase_identifiers_verified':True,
            'matched':{clazz.rsplit('.',1)[-1]:[method]},
            'source':'androidx-test-runner-direct-host-instrumentation',
            'clear_package_data':False,'orchestrator_bypassed_for_persistent_state':True}

def direct_stage_diagnostic(stdout:str,stderr:str,returncode:int,selector:str)->dict:
    """Fixed fields only; never log test stream, packages, tokens or secrets."""
    text=(stdout or '')[:256_000]
    lines=[v.strip() for v in text.splitlines()]
    codes=[v.split(':',1)[1].strip() for v in lines if v.startswith('INSTRUMENTATION_STATUS_CODE:')]
    final=[v.split(':',1)[1].strip() for v in lines if v.startswith('INSTRUMENTATION_CODE:')]
    return {'stage':selector.rsplit('#',1)[-1], 'shell_exit_code':int(returncode),
            'stdout_bytes':len((stdout or '').encode('utf8')),
            'stderr_bytes':len((stderr or '').encode('utf8')),
            'stdout_sha256':sha((stdout or '').encode('utf8')),
            'stderr_sha256':sha((stderr or '').encode('utf8')),
            'status_codes':codes[:8], 'final_codes':final[:4],
            'status_code_count':len(codes),'runner_final_count':len(final),
            'failure_marker':any(('INSTRUMENTATION_FAILED' in v or
                'INSTRUMENTATION_ABORTED' in v or 'shortMsg=' in v or
                'FAILURES!!!' in v) for v in lines)}

def direct_stage(serial:str,selector:str)->dict:
    """Run exactly one signed AndroidJUnitRunner test without Orchestrator data wipe."""
    need(selector in (
      'io.github.mikeceead.xbscompanion.Gx03ProcessDeathInstrumentedTest#stageAWriteDurablePendingOwnership',
      'io.github.mikeceead.xbscompanion.Gx03ProcessDeathInstrumentedTest#stageBInspectRecoveryAfterExternalForceStop'),
      'DIRECT_PROCESS_STAGE_NOT_ALLOWLISTED')
    # -w alone decodes human-readable output and hides raw per-test codes.
    # -r is essential for the exact INSTRUMENTATION_STATUS_CODE evidence contract.
    args=('shell','am','instrument','-w','-r','-e','clearPackageData','false',
          '-e','class',selector,PKG+'.test/androidx.test.runner.AndroidJUnitRunner')
    completed=adb(serial,*args,timeout=300,check=False)
    try:
        need(completed.returncode==0,'DIRECT_STAGE_ADB_EXIT_NONZERO')
        receipt=verified_direct_stage_receipt(completed.stdout,selector)
    except Refused:
        print('GX03_DIRECT_STAGE_DIAGNOSTIC '+json.dumps(direct_stage_diagnostic(
            completed.stdout,completed.stderr,completed.returncode,selector),
            sort_keys=True),file=sys.stderr,flush=True)
        raise
    print('GX03_DIRECT_PROCESS_STAGE_PASS '+selector.rsplit('#',1)[-1],flush=True)
    return receipt

def check_installed_pair(serial:str)->None:
    # A real PM registration is mandatory, not a fabricated success from Gradle.
    for package in (PKG,PKG+'.test'):
        result=adb(serial,'shell','pm','path',package,check=False)
        need(result.returncode==0 and
             any(line.startswith('package:') for line in result.stdout.splitlines()),
             'DIRECT_STAGE_APP_OR_TEST_NOT_INSTALLED '+package)

def install_verified_pair_for_stage(serial:str,verified:dict)->None:
    # Runs only inside disposable hosted emulator, after signer/package checks.
    base=ROOT/'android-app/app/build/outputs/apk'
    for kind,relative in (('app','debug/app-debug.apk'),
                          ('test','androidTest/debug/app-debug-androidTest.apk')):
        source=base/relative
        need(source.is_file() and not source.is_symlink() and source.stat().st_size>10_000,
             'DIRECT_STAGE_VERIFIED_APK_MISSING '+relative)
        need(sha(source.read_bytes())==verified[kind]['sha256'],
             'DIRECT_STAGE_PREINSTALL_SIGNED_PAIR_SHA_DRIFT '+kind)
        installed=adb(serial,'install','-r',str(source),timeout=300)
        need('Success' in installed.stdout,'DIRECT_STAGE_INSTALL_NOT_CONFIRMED '+relative)
    check_installed_pair(serial)

def process_death(serial):
    cls='io.github.mikeceead.xbscompanion.Gx03ProcessDeathInstrumentedTest'
    # Only the two persistence stages bypass AndroidX Test Orchestrator. Its
    # connectedDebugAndroidTest lifecycle can uninstall/clear the app between
    # individual Gradle invocations, invalidating an actual process-death test.
    check_installed_pair(serial)
    first=direct_stage(serial,cls+'#stageAWriteDurablePendingOwnership')
    # DO NOT reinstall or clear package data between the two stages.
    check_installed_pair(serial)
    adb(serial,'shell','am','start','-W','-n',PKG+'/.MainActivity')
    pid=adb(serial,'shell','pidof',PKG,check=False)
    need(pid.returncode==0 and pid.stdout.strip().isdigit(),'PROCESS_DEATH_TARGET_NOT_ALIVE_BEFORE_KILL')
    oldpid=pid.stdout.strip()
    adb(serial,'shell','am','force-stop',PKG)
    pid_after=adb(serial,'shell','pidof',PKG,check=False)
    need(pid_after.returncode!=0 or not pid_after.stdout.strip(),
         'FORCE_STOP_DID_NOT_TERMINATE_APP')
    second=direct_stage(serial,cls+'#stageBInspectRecoveryAfterExternalForceStop')
    return {'stage_a':first,'stage_b':second,'host_force_stop_executed':True,
            'old_process_pid_observed':bool(oldpid),'post_kill_absent':True,
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
        # GX04: actual API36 instrumented package/TileService security checks.
        # Mandatory, never synthetic SystemUI acceptance or root qualification.
        gradle_test('io.github.mikeceead.xbscompanion.Gx04TilesInstrumentedTest')
        report['gx04_tile_assertions']=junit_receipt(RESULTS,{
            'Gx04TilesInstrumentedTest':{
                'allFiveTilesHaveSystemOnlyManifestPermission',
                'allTileServicesAreBoundToQuickSettingsAction',
                'unsafeAndUnconfiguredTilesNeverClaimActive',
                'noConfiguredRestoreRootActionAndAllEventActionsAllowlisted',
                'hostileEventNamesRejectedBeforeLaunch',
                'verifiedCompletionRequiresTerminalHistoryAndNewerEvidence',
                'unverifiedTilesCannotLaunchRootCommands',
                'runtimeSettingsActivityRendersAllFiveTileRequests',
                'durablePendingBlocksRepeatedTapAcrossReload',
                'r08MatrixForbidsAnyActionDuringPendingFailureOrRecovery',
                'r08EventTilesRequireConfiguredAuthenticatedUnpausedState',
                'r08RecoveryCannotBeAcknowledgedWhileCommandPending',
                'r08RestoreDefaultsAlwaysRequiresSeparateAppConfirmation',
                'r10SettingsFiveTileFallbackSurvivesActivityRecreation',
                'r10SystemUiCallbackCountersCannotAuthenticateRootlessTiles'
            }
        })
        # GX05: keep Python adversarial gates mandatory in the hosted API36 lane.
        gx05_unit=subprocess.run([sys.executable,'-B','-m','unittest','discover',
            '-s','scripts/ci','-p','test_gx05_*.py','-v'],cwd=ROOT,timeout=120,check=False)
        need(gx05_unit.returncode==0,'GX05_ADVERSARIAL_HOSTED_TESTS_FAILED')
        # GX05: real rootless Android security assertions, never a source-only PASS.
        gradle_test('io.github.mikeceead.xbscompanion.Gx05PreflightInstrumentedTest')
        report['gx05_security_assertions']=junit_receipt(RESULTS,{
            'Gx05PreflightInstrumentedTest':{
                'privilegedForegroundServicesNotExported',
                'notificationAndBootReceiversNotExported',
                'allSystemUiTilesBoundBySystemPermission',
                'hostileTaskerIntentWithoutAuthorizationCannotStartCommand',
                'unverifiedSnapshotCannotAuthorizeQuickTiles',
                'readOnlyStateReadsDoNotLeakDescriptors',
                'readOnlyStateReadsDoNotSpawnUnboundedWorkers'
            }
        })
        # Verify & snapshot signed APKs before manual installation, ensuring
        # that no Gradle Test Orchestrator uninstalls stage-A data mid-journey.
        report['apk_pair']=pair(serial,identity,str(apksigner),str(aapt))
        install_verified_pair_for_stage(serial,report['apk_pair'])
        report['gx04_systemui_callbacks']=gx04_systemui_probe(serial)
        report['process_death']=process_death(serial)
        # GX05: real per-invocation cold-start and total-PSS measurements.
        # No battery savings, wakeup rate, physical-root or signing claim.
        from gx05_hosted_measure import collect as gx05_collect
        from gx05_preflight import evidence as gx05_evidence, digest as gx05_digest
        measured=gx05_collect(serial,adb,PKG,identity)
        out=OUT/'api36';out.mkdir(parents=True,exist_ok=True)
        security=report['gx05_security_assertions']
        require_security=security['required_testcases']==7 and security['skipped_cases']==0 and security['failed_cases']==0
        need(require_security,'GX05_MANDATORY_SECURITY_ASSERTIONS_MISSING')
        raw_files={
            'gx05-startup-samples.json':json.dumps(measured,sort_keys=True,indent=2).encode(),
            'gx05-security-tests.json':json.dumps(security,sort_keys=True,indent=2).encode()
        }
        artifact_rows=[]
        for name,blob in sorted(raw_files.items()):
            dest=out/name
            need(not dest.exists(),'GX05_PREVIOUS_RUN_ARTIFACT_REFUSED '+name)
            dest.write_bytes(blob)
            need(gx05_digest(dest.read_bytes())==gx05_digest(blob),'GX05_ARTIFACT_WRITE_MISMATCH')
            artifact_rows.append({'path':name,'sha256':gx05_digest(blob),'size':len(blob),
                'source_sha':identity['source_sha'],'run_id':identity['github_run_id'],
                'run_attempt':identity['github_run_attempt']})
        report['gx05_preflight']=gx05_evidence(
            identity={'source_sha':identity['source_sha'],'run_id':identity['github_run_id'],
                      'run_attempt':identity['github_run_attempt'],'event':os.getenv('GITHUB_EVENT_NAME'),
                      'hosted':os.getenv('GITHUB_ACTIONS')=='true'},
            performance=measured,artifacts=artifact_rows,
            security={'instrumented_count':security['required_testcases'],
                      'failed':security['failed_cases'],'skipped':security['skipped_cases'],
                      'exported_components_verified':True,'untrusted_receiver_tests':True})
        print('GX05_ROOTLESS_SECURITY_PERFORMANCE_PASS source='+identity['source_sha']+
            ' run='+identity['github_run_id']+' attempt='+identity['github_run_attempt']+
            ' samples=7 physical_qualified=false release_qualified=false',flush=True)
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
    if args.mode=='compat':
        report['apk_pair']=pair(serial,identity,str(apksigner),str(aapt))
    out=OUT/('api'+str(args.api));out.mkdir(parents=True,exist_ok=True)
    dest=out/'evidence.json'
    need(not dest.exists(),'STALE_ANDROID_RECEIPT_REFUSED')
    dest.write_text(json.dumps(report,indent=2,sort_keys=True)+'\n')
    need(json.loads(dest.read_text())==report,'ANDROID_RECEIPT_WRITE_MISMATCH')
    print('GX03_HOSTED_ANDROID_EVIDENCE_PASS api='+str(args.api)+' source='+identity['source_sha']+
          ' run='+identity['github_run_id']+' attempt='+identity['github_run_attempt']+
          ' physical_root_qualified=false original_cases_accepted=0',flush=True)



def gx04_systemui_counts(serial):
    result=adb(serial,'shell','run-as',PKG,'cat','shared_prefs/gx04_tile_lifecycle_v1.xml',check=False)
    if result.returncode:
        need('No such file' in (result.stdout+result.stderr),
             'GX04_SYSTEMUI_PRIVATE_PROBE_UNAVAILABLE '+(result.stderr or '')[-280:])
        return {}
    need(len(result.stdout)<8192,'GX04_SYSTEMUI_COUNTER_XML_OVERSIZED')
    values={}
    for key,amount in re.findall(r'<int\s+name="([a-z]+_[A-Z]+)"\s+value="([0-9]+)"',result.stdout):
        values[key]=int(amount)
    return values

def gx04_systemui_probe(serial):
    """Rootless SystemUI add/click/remove: verify callbacks, never shell exit alone.

    A freshly installed app has no authenticated XBS daemon; its tile must
    remain visually non-active yet allow an app-diagnostic click. This is not
    evidence that any privileged command was executed.
    """
    import time
    service=PKG+'/.XbsPauseTileService'
    before=gx04_systemui_counts(serial)
    observed={}
    commands=[]
    last_counts=dict(before)
    def statusbar(command,component=False):
        args=('shell','cmd','statusbar',command,service) if component else ('shell','cmd','statusbar',command)
        result=adb(serial,*args,check=False)
        need(result.returncode==0,'GX04_SYSTEMUI_COMMAND_REFUSED '+command+' '+(result.stderr or '')[-300:])
        commands.append(command)
    def wait_delta(marker,intervals=80):
        nonlocal last_counts
        for _ in range(intervals):
            last_counts=gx04_systemui_counts(serial)
            if last_counts.get(marker,0)>before.get(marker,0):
                observed[marker]=last_counts[marker]
                return True
            time.sleep(0.25)
        return False
    added=False
    try:
        check=adb(serial,'shell','cmd','statusbar','check-support',check=False)
        need(check.returncode==0 and check.stdout.strip()=='true','GX04_SYSTEMUI_NOT_SUPPORTED')
        statusbar('add-tile',True)
        added=True
        need(wait_delta('added_PAUSE'),
             'GX04_SYSTEMUI_CALLBACK_MISSING added_PAUSE counts='+repr(last_counts))
        # SystemUI may not dispatch clicks until QS is expanded/listening;
        # do not infer a click from exit code 0 (shell call is asynchronous).
        statusbar('expand-settings')
        time.sleep(1)
        statusbar('click-tile',True)
        if not wait_delta('clicked_PAUSE',intervals=64):
            # Bound one fresh UI activation/retry; no root operation is permitted
            # because a fresh emulator lacks an authenticated daemon snapshot.
            statusbar('collapse')
            statusbar('expand-settings')
            time.sleep(1)
            statusbar('click-tile',True)
            need(wait_delta('clicked_PAUSE',intervals=64),
                 'GX04_SYSTEMUI_CALLBACK_MISSING clicked_PAUSE counts='+repr(last_counts)+
                 ' commands='+repr(commands))
    finally:
        if added:
            statusbar('remove-tile',True)
        statusbar('collapse')
    need(wait_delta('removed_PAUSE'),
         'GX04_SYSTEMUI_REMOVE_CALLBACK_MISSING counts='+repr(last_counts))
    print('GX04_SYSTEMUI_ROOTLESS_CALLBACKS_PASS added=true clicked=true removed=true',flush=True)
    return {'real_systemui_shell':True,'add_callback':True,'click_callback':True,
            'remove_callback':True,'api':36,'user_data_recorded':False,
            'physical_oem_qualified':False,'root_execution_asserted':False,
            'shell_exit_alone_never_proves_callback':True,
            'diagnostic_states_remain_non_active':True}


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--mode',required=True,choices=['preflight','api36','compat'])
    parser.add_argument('--api',required=True,type=int,choices=[26,34,36])
    args=parser.parse_args()
    try:execute(args)
    except (Refused,ValueError,KeyError,OSError,subprocess.TimeoutExpired,subprocess.CalledProcessError) as e:
        print('GX03_HOSTED_REFUSED '+str(e),file=sys.stderr,flush=True)
        sys.exit(2)
