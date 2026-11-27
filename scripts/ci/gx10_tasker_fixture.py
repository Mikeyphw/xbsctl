"""Disposable, signed, separate-UID Tasker host fixtures for the API36 hosted lane.

The packages are never part of XBS, never published as artifacts, and never
installed outside the exact hosted emulator. Signed editor bundles stay in
fixture process memory; no raw UI dumps or payloads are printed or published.
"""
from __future__ import annotations
import re
import subprocess
import os
import time
import tempfile
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path
from gx03_evidence import PKG, Refused, need, sha

TRUSTED='net.dinglisch.android.taskerm'
UNTRUSTED='io.github.mikeceead.xbsfixture.untrusted'
ACTIVITY='io.github.mikeceead.xbsfixture.HostActivity'
MODES={'state':('Save state',{16,17}), 'event':('Save event',{16}), 'action':('Save Tasker action',{2,3})}


def parse_window(raw: bytes):
    need(isinstance(raw,bytes) and 0<len(raw)<=512_000 and b'<!DOCTYPE' not in raw.upper()
         and b'<!ENTITY' not in raw.upper(),'TASKER_WINDOW_UNSAFE')
    try: tree=ET.fromstring(raw)
    except ET.ParseError as error: raise Refused("TASKER_WINDOW_INVALID_XML") from error
    need(tree.tag=='hierarchy','TASKER_WINDOW_INVALID')
    return list(tree.iter('node'))


def visible_center(nodes,package,label):
    matched=[node for node in nodes if node.get('package')==package and
             node.get('text','').casefold()==label.casefold() and node.get('enabled')=='true']
    need(len(matched)<=1,'TASKER_CONTROL_AMBIGUOUS')
    if not matched: return None
    rect=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',matched[0].get('bounds',''))
    need(rect is not None,'TASKER_CONTROL_BOUNDS_INVALID')
    x,y,X,Y=map(int,rect.groups())
    need(0<=x<X<=4000 and 0<=y<Y<=4000,'TASKER_CONTROL_OFFSCREEN')
    return ((x+X)//2,(y+Y)//2)


def fixture_result(nodes,package,mode):
    statuses=[n.get('text','') for n in nodes if n.get('package')==package and
              n.get('text','').startswith('XBS_FIXTURE_')]
    need(len(statuses)<=1,'TASKER_FIXTURE_STATUS_AMBIGUOUS')
    if not statuses or statuses[0]=='XBS_FIXTURE_WAIT': return None
    if mode=='untrusted_editor':
        need(statuses==['XBS_FIXTURE_PASS_UNTRUSTED_EDITOR'],'TASKER_UNTRUSTED_EDITOR_NOT_REFUSED')
        return {'untrusted_editor_refused':True}
    match=re.fullmatch('XBS_FIXTURE_PASS_'+mode.upper()+r'_([0-9]+)',statuses[0])
    need(match is not None and int(match.group(1)) in MODES[mode][1], 'TASKER_SIGNED_JOURNEY_FAILED '+mode)
    return {'real_editor_result':True,'positive_ordered_result':int(match.group(1)),
            'tampered_signature_refused':True,'hidden_identity_refused':True,'other_uid_replay_refused':True}


def verify_apk(path,package,apksigner,aapt,run):
    need(path.is_file() and not path.is_symlink() and 1000<path.stat().st_size<100_000_000,'TASKER_FIXTURE_APK_INVALID')
    info=run([str(aapt),'dump','badging',str(path)])
    need(re.search(r"(?m)^package: name='"+re.escape(package)+r"'",info.stdout) is not None,'TASKER_FIXTURE_PACKAGE_DRIFT')
    cert=run([str(apksigner),'verify','--print-certs',str(path)])
    digests=re.findall(r'Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})',cert.stdout)
    need(len(digests)==1,'TASKER_FIXTURE_SIGNER_AMBIGUOUS')
    return {'sha256':sha(path.read_bytes()),'certificate_sha256':digests[0].lower(),'package':package}


def collect(root:Path,serial:str,identity:dict,adb,run,apksigner,aapt):
    need(os.getenv('GITHUB_ACTIONS')=='true' and os.getenv('RUNNER_ENVIRONMENT')=='github-hosted' and
         identity.get('api_level')==36 and serial==identity.get('serial') and serial.startswith('emulator-') and
         os.getenv('XBS_EXPECTED_HEAD_SHA')==identity.get('source_sha'), 'TASKER_FIXTURE_HOST_NOT_API36')
    need(adb(serial,'shell','getprop','ro.kernel.qemu').stdout.strip()=='1' and
         adb(serial,'shell','getprop','ro.build.version.sdk').stdout.strip()=='36' and
         adb(serial,'shell','id','-u').stdout.strip()=='2000','TASKER_FIXTURE_NOT_ROOTLESS_EMULATOR')
    packages=(TRUSTED,UNTRUSTED,'net.dinglisch.android.tasker')
    for package in packages:
        found=adb(serial,'shell','pm','path',package,check=False)
        need('package:' not in found.stdout,'TASKER_FIXTURE_PREEXISTING_PACKAGE_REFUSED')
    # SDK-only test APKs: no new production Gradle root, dependency or artifact.
    # The fixture is scoped to the disposable hosted emulator and is compiled
    # against the same pinned SDK as the product. Nothing is distributed.
    with tempfile.TemporaryDirectory(prefix='xbs-tasker-host-') as directory:
        apks=build_fixtures(root,Path(directory),Path(apksigner),run)
        return exercise(root,serial,identity,adb,run,apksigner,aapt,apks)


def build_fixtures(root, work, apksigner, run):
    tools=apksigner.parent
    android=tools.parents[1]/'platforms/android-36/android.jar'
    need(android.is_file() and all((tools/x).is_file() for x in ('d8','aapt2','zipalign')),
         'TASKER_FIXTURE_SDK_TOOLS_MISSING')
    source=root/'android-app/tasker-host-fixture/src/main'
    java=source/'java/io/github/mikeceead/xbsfixture/HostActivity.java'
    classes=work/'classes';classes.mkdir()
    dex=work/'dex';dex.mkdir()
    run(['javac','--release','8','-cp',str(android),'-d',str(classes),str(java)],timeout=90)
    compiled=sorted(classes.rglob('*.class'))
    need(bool(compiled),'TASKER_FIXTURE_CLASSES_MISSING')
    run([str(tools/'d8'),'--min-api','34','--lib',str(android),'--output',str(dex),
         *[str(p) for p in compiled]],timeout=90)
    need((dex/'classes.dex').is_file(),'TASKER_FIXTURE_DEX_MISSING')
    key=work/'fixture.p12'
    run(['keytool','-genkeypair','-alias','fixture','-keyalg','RSA','-keysize','2048',
         '-validity','3','-storetype','PKCS12','-keystore',str(key),
         '-storepass','fixture-only','-keypass','fixture-only','-dname','CN=XBS disposable CI fixture'],timeout=30)
    apks={}
    for package in (TRUSTED,UNTRUSTED):
        folder=work/package;folder.mkdir()
        manifest=folder/'AndroidManifest.xml'
        tree=ET.parse(source/'AndroidManifest.xml');tree.getroot().set('package',package)
        ET.register_namespace('android','http://schemas.android.com/apk/res/android')
        tree.write(manifest,encoding='utf-8',xml_declaration=True)
        raw=folder/'unsigned.apk';aligned=folder/'aligned.apk';final=folder/'fixture.apk'
        run([str(tools/'aapt2'),'link','-I',str(android),'--manifest',str(manifest),'-o',str(raw)],timeout=90)
        with zipfile.ZipFile(raw,'a',compression=zipfile.ZIP_STORED) as archive:
            archive.write(dex/'classes.dex','classes.dex')
        run([str(tools/'zipalign'),'-f','4',str(raw),str(aligned)],timeout=90)
        run([str(apksigner),'sign','--ks',str(key),'--ks-key-alias','fixture','--ks-pass','pass:fixture-only',
             '--key-pass','pass:fixture-only','--out',str(final),str(aligned)],timeout=90)
        apks[package]=final
    return apks


def exercise(root,serial,identity,adb,run,apksigner,aapt,apks):
    evidence={p:verify_apk(apk,p,apksigner,aapt,run) for p,apk in apks.items()}
    need(evidence[TRUSTED]['sha256']!=evidence[UNTRUSTED]['sha256'],'TASKER_FIXTURE_APKS_IDENTICAL')
    target=root/'android-app/app/build/outputs/apk/debug/app-debug.apk'
    target_signer=verify_apk(target,PKG,apksigner,aapt,run)['certificate_sha256']
    need(all(row['certificate_sha256']!=target_signer for row in evidence.values()),
         'TASKER_FIXTURE_SHARES_APP_SIGNER')
    installed=[]
    remote='/data/local/tmp/gx10-tasker-window.xml'
    def nodes():
        try:
            adb(serial,'shell','rm','-f',remote)
            adb(serial,'shell','uiautomator','dump','--compressed',remote,timeout=45)
            data=adb(serial,'exec-out','cat',remote,bytes_mode=True)
            return parse_window(data.stdout)
        finally:
            adb(serial,'shell','rm','-f',remote)
    def tap(label,scroll=False):
        for attempt in range(8):
            current=nodes()
            point=visible_center(current,PKG,label)
            if point is not None:
                adb(serial,'shell','input','tap',str(point[0]),str(point[1]));return
            if attempt<7:
                if scroll:
                    bounds=[n.get('bounds','') for n in current if n.get('package')==PKG and n.get('scrollable')=='true']
                    if bounds:
                        match=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',bounds[0])
                        need(match is not None,'TASKER_SCROLL_BOUNDS_INVALID')
                        x,y,X,Y=map(int,match.groups())
                        need(0<=x<X<=4000 and 0<=y<Y<=4000 and Y-y>=120,'TASKER_SCROLL_BOUNDS_UNSAFE')
                        middle=(x+X)//2
                        adb(serial,'shell','input','swipe',str(middle),str(Y-(Y-y)//5),str(middle),str(y+(Y-y)//5),'400')
                time.sleep(.25)
        raise RuntimeError('TASKER_REAL_EDITOR_CONTROL_MISSING '+label)
    def wait_result(package,mode):
        for _ in range(22):
            result=fixture_result(nodes(),package,mode)
            if result is not None: return result
            time.sleep(.25)
        raise RuntimeError('TASKER_FIXTURE_RESULT_TIMEOUT '+mode)
    rows={}
    try:
        for package,apk in apks.items():
            need(sha(apk.read_bytes())==evidence[package]['sha256'],'TASKER_FIXTURE_PREINSTALL_HASH_DRIFT')
            # No -r: preexisting packages must never be replaced.
            result=adb(serial,'install',str(apk),timeout=120)
            need('Success' in result.stdout,'TASKER_FIXTURE_INSTALL_REFUSED');installed.append(package)
        uids={}
        for package in apks:
            out=adb(serial,'shell','pm','list','packages','-U',package).stdout
            match=re.search(r'(?m)^package:'+re.escape(package)+r' uid:(\d+)\s*$',out)
            need(match is not None,'TASKER_FIXTURE_UID_MISSING');uids[package]=int(match.group(1))
        need(uids[TRUSTED]!=uids[UNTRUSTED],'TASKER_FIXTURE_UID_COLLISION')
        for mode in ('untrusted_editor','state','event','action'):
            package=UNTRUSTED if mode=='untrusted_editor' else TRUSTED
            adb(serial,'shell','am','force-stop',package)
            started=adb(serial,'shell','am','start','-W','-n',package+'/'+ACTIVITY,'--es','mode',mode)
            need(re.search(r'(?m)^Status:\s*ok\s*$',started.stdout) is not None,'TASKER_FIXTURE_ACTIVITY_NOT_STARTED')
            if mode!='untrusted_editor':
                if mode=='action':
                    tap('General · XBS help');tap('General · Runtime status')
                tap(MODES[mode][0],scroll=True)
            rows[mode]=wait_result(package,mode)
    finally:
        clean=True
        for package in reversed(installed):
            try:
                result=adb(serial,'uninstall',package,timeout=90,check=False)
                clean=clean and result.returncode==0 and 'Success' in result.stdout
            except (OSError,subprocess.TimeoutExpired): clean=False
        # Disposable test installation only; remove the fake Tasker certificate pin
        # before later captures. No real Tasker package was present on entry.
        adb(serial,'shell','am','force-stop',PKG)
        pin=adb(serial,'shell','run-as',PKG,'rm','-f','shared_prefs/tasker_host_pin_v2.xml',check=False)
        need(clean and pin.returncode==0,'TASKER_FIXTURE_CLEANUP_FAILED')
    return {'schema':'xbs-gx10-controlled-tasker-host/v1','source_sha':identity['source_sha'],
            'run_id':identity['github_run_id'],'run_attempt':identity['github_run_attempt'],
            'fixture_apks':evidence,'distinct_uids_verified':True,'assertions':rows,
            'cleanup_verified':True,'signed_bundles_published':False,
            'real_tasker_certified':False,'physical_qualified':False,'root_effects_qualified':False}
