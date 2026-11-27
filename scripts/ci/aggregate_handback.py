#!/usr/bin/env python3
"""Replay frozen HF01 hosted evidence; never promote original case acceptance.

Archive replay requires all seven exact GitHub-downloaded archives. On checkout
only, the committed observation and original inventories are checked, not claimed
as fresh artifact downloads or runtime executions.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import stat
import subprocess
import zipfile

HEAD='455c7493ee3b14e7ac5d0595d82daf2f69983e0f'
TREE='ec485329264b86501fd70e76accc8e37ac060071'
FILE='docs/engineering/XBS_AGG_INT01_EVIDENCE.json'
ROOT=Path(__file__).resolve().parents[2]
# artifact id, workflow id, run id, provider SHA-256, byte count, exact member set
PINS={
 'api36':(15166985751,386774409,45925618088,'13e0e09102ef61c89a621ba5c8e59b8e007eb234090a27fed91b4ba134ea0249',9055737,('app-debug.apk','test-debug.apk','evidence.json','gx05-security-tests.json','gx05-startup-samples.json',*(f'gx03-{w}dp-{t}-redacted.png' for w in (360,393) for t in ('light','dark')))),
 'r05c-api36':(15166706505,386774409,45925618088,'5cf86feb2c9577168503505c64c99099e5325e0c8b50aceb7e4b45948662587b',5636115,('xbs-companion-r05c-api36-debug.apk','SHA256SUMS','SOURCE_SHA')),
 'module':(15166481908,386774409,45925618088,'a3131641804f52c0e3a1b07a3ed18153c4941a267217016661628937699635d9',6533858,('XtremeBS-go.zip','SHA256SUMS','r06b-devtool-qualification.json','r06b-go-test-counts.json','r09a-backend-qualification.json','r09a-backend.coverprofile','r09b-backend-matrix.json','r09c-backend-model.json','r09c-backend.coverprofile','r09d-risk-coverage.json','r09d-risk.coverprofile','r09e-boundary-coverage.json','r09f-final-ledger.json')),
 'jvm':(15166431868,386774409,45925618088,'bfb063cb780f06496cbd1806b92baa46294a455faa03d15687640ea22a88bae7',376,('r06b-jvm-test-counts.json',)),
 'api26':(15166507088,419253895,45925618029,'e66dbf368dcde4ac6a462c5e4d5784e254be410db873da1912403e946891fc2a',8563426,('app-debug.apk','test-debug.apk','evidence.json')),
 'api34':(15166517568,419253895,45925618029,'13d25122a702f13a9c899772d251db8372a58aba0cf427cd607cfce7b6ee4516',8563441,('app-debug.apk','test-debug.apk','evidence.json')),
 's7':(15165784304,418896455,45925618098,'dab96489050ad80347de5291bf3366c5497b37bba501967b9dce3d60f8cc2384',5636057,('xbs-debug.apk','SHA256SUMS','SOURCE_SHA')),
}
OBSERVATION_SHA='25ec1ee9d1f9ead33fec0593fe79384e631303698d67d3da30e7e80a60073ee4'
class Refused(ValueError): pass

def need(ok,reason):
    if not ok: raise Refused('INT01_'+reason)

def sha(data): return hashlib.sha256(data).hexdigest()
def stable(data): return json.dumps(data,sort_keys=True,separators=(',',':'),ensure_ascii=True).encode()

def archive(path,key):
    aid,wid,run,digest,size,expected=PINS[key]
    need(path.is_file() and not path.is_symlink() and path.stat().st_size==size,'ARCHIVE_SIZE_OR_PATH '+key)
    need(sha(path.read_bytes())==digest,'PROVIDER_DIGEST '+key)
    with zipfile.ZipFile(path) as z:
        infos=z.infolist()
        need(len(infos)==len(expected) and {x.filename for x in infos}==set(expected),'ZIP_MEMBERS '+key)
        need(sum(x.file_size for x in infos)<120_000_000,'ZIP_SIZE '+key)
        raw={}
        for x in infos:
            name=x.filename
            need(PurePosixPath(name).name==name and '\\' not in name and name not in ('.','..') and
                 not x.is_dir() and not stat.S_ISLNK(x.external_attr>>16) and not x.flag_bits&1 and
                 0<x.file_size<60_000_000,'ZIP_UNSAFE_MEMBER '+name)
            raw[name]=z.read(x)
        need(z.testzip() is None,'ZIP_CRC '+key)
    rec={'artifact_id':aid,'workflow_id':wid,'run_id':run,'attempt':1,'source_sha':HEAD,
         'provider_sha256':digest,'archive_size_bytes':size,'members':{n:{'sha256':sha(v),'size_bytes':len(v)} for n,v in sorted(raw.items())}}
    return rec,raw

def identity(d,run,source_key='source_sha'):
    need(d[source_key]==HEAD and str(d['github_run_id'])==str(run) and str(d['github_run_attempt'])=='1','RECEIPT_IDENTITY')

def assertions(receipt,expected):
    count=sum(map(len,expected.values()))
    need({k:set(v) for k,v in receipt['matched'].items()}==expected and
         all(len(v)==len(set(v)) for v in receipt['matched'].values()) and
         receipt['required_testcases']==receipt['total_testcases']==count and
         receipt['failed_cases']==receipt['skipped_cases']==0 and receipt['testcase_identifiers_verified'] is True,
         'NAMED_ASSERTION_RECEIPT')
    return receipt['matched']

def checksum(raw,name,source=False):
    text=raw['SHA256SUMS'].decode('ascii').split()
    need(len(text)==2 and text[0]==sha(raw[name]) and text[1] in (name,'dist/'+name),'CHECKSUM '+name)
    if source: need(raw['SOURCE_SHA'].decode('ascii').strip()==HEAD,'APK_SOURCE')


def replay(directory):
    from gx03_evidence import JOURNEYS,STAGE_A,STAGE_B,COMPAT,PKG
    from r08_r10_acceptance import HISTORICAL_TILE_METHODS,METHODS
    from gx05_preflight import measured_android
    rows={};contents={}
    for key in PINS:
        rows[key],contents[key]=archive(directory/f'xbs-{key}-hf01.zip',key)
    out={'archives':rows,'android':{},'test_counts':{},'backend':{}}
    for api in (26,34,36):
        key=f'api{api}';raw=contents[key];d=json.loads(raw['evidence.json']);identity(d,PINS[key][2])
        need(d['api_level']==api and d['emulator_only'] is True and d['qemu'] in ('1',1,True) and
             d['lane']=='ROOTLESS_ANDROID_EMULATOR' and d['original_cases_accepted']==[], 'EMULATOR_BOUNDARY')
        for field in ('physical_root_qualified','kernel_su_qualified','magisk_qualified','signed_release','battery_savings_measured'):
            need(d[field] is False,'ANDROID_OVERCLAIM '+field)
        pair=d['apk_pair']
        for kind,package in [('app',PKG),('test',PKG+'.test')]:
            item=pair[kind];name=kind+'-debug.apk'
            need(item['sha256']==sha(raw[name]) and item['size_bytes']==len(raw[name]) and item['package']==package,'APK_PAIR '+kind)
        need(pair['app']['cert_sha256']==pair['test']['cert_sha256'] and pair['app']['sha256']!=pair['test']['sha256'],'APK_SIGNER_OR_IDENTITY')
        result={'apk_pair_receipt':pair,'source_sha':HEAD,'run_id':PINS[key][2],'attempt':1,
                'signer_package_observation':'HOSTED_RECEIPT_NOT_LOCAL_APKSIGNER'}
        if api!=36:
            result['assertions']=assertions(d['compat_assertions'],COMPAT[api])
        else:
            result['assertions']=assertions(d['journey_assertions'],JOURNEYS)
            result['tiles']=assertions(d['gx04_tile_assertions'],{'Gx04TilesInstrumentedTest':set(HISTORICAL_TILE_METHODS+METHODS)})
            death=d['process_death']
            need(all(death[k] is True for k in ('host_force_stop_executed','old_process_pid_observed','post_kill_absent')) and
                 death['claimed_no_automatic_replay'] is False and death['root_command_effects_executed'] is False,'PROCESS_DEATH_SCOPE')
            result['process_death']={'stage_a':assertions(death['stage_a'],STAGE_A),'stage_b':assertions(death['stage_b'],STAGE_B),
                                     'host_force_stop_observed':True,'automatic_replay_qualified':False}
            sec={'Gx05PreflightInstrumentedTest':{'allSystemUiTilesBoundBySystemPermission','hostileTaskerIntentWithoutAuthorizationCannotStartCommand','notificationAndBootReceiversNotExported','privilegedForegroundServicesNotExported','readOnlyStateReadsDoNotLeakDescriptors','readOnlyStateReadsDoNotSpawnUnboundedWorkers','unverifiedSnapshotCannotAuthorizeQuickTiles'}}
            need(d['gx05_security_assertions']==json.loads(raw['gx05-security-tests.json']),'SECURITY_RECEIPT_DRIFT')
            result['security']=assertions(d['gx05_security_assertions'],sec)
            metrics=measured_android(json.loads(raw['gx05-startup-samples.json']))
            need(metrics==d['gx05_preflight']['performance'] and d['gx05_preflight']['source_sha']==HEAD and d['gx05_preflight']['run_id']==str(PINS[key][2]) and d['gx05_preflight']['run_attempt']=='1','METRICS_RECEIPT')
            result['startup']=metrics
            result['systemui_callbacks']=d['gx04_systemui_callbacks']
            need(all(result['systemui_callbacks'][k] is True for k in ('real_systemui_shell','add_callback','click_callback','remove_callback','diagnostic_states_remain_non_active')) and result['systemui_callbacks']['root_execution_asserted'] is False,'SYSTEMUI_BOUNDARY')
            result['screenshots']=[]
            import struct
            for shot in d['screenshots']:
                name=shot['file'];blob=raw[name]
                need(sha(blob)==shot['redacted_sha256'] and len(blob)==shot['redacted_size_bytes'] and blob[:8]==b'\x89PNG\r\n\x1a\n' and struct.unpack('>II',blob[16:24])==(shot['width_px'],shot['height_px']),'SCREENSHOT_IDENTITY')
                result['screenshots'].append({k:shot[k] for k in ('file','width_dp','width_px','height_px','theme_requested','redacted_sha256','redaction_scope','locale_rtl_accepted','accessibility_accepted')})
            need(len(result['screenshots'])==4 and {(x['width_dp'],x['theme_requested']) for x in result['screenshots']}=={(w,t) for w in (360,393) for t in ('light','dark')},'SCREENSHOT_MATRIX')
            result['distinct_theme_content_by_width']={str(w):len({x['redacted_sha256'] for x in result['screenshots'] if x['width_dp']==w})==2 for w in (360,393)}
            need(not any(result['distinct_theme_content_by_width'].values()),'HISTORICAL_THEME_GAP_DRIFT')
        out['android'][key]=result
    for key,name in [('r05c-api36','xbs-companion-r05c-api36-debug.apk'),('s7','xbs-debug.apk'),('module','XtremeBS-go.zip')]:checksum(contents[key],name,key!='module')
    need(sha(contents['r05c-api36']['xbs-companion-r05c-api36-debug.apk'])==out['android']['api36']['apk_pair_receipt']['app']['sha256'],'API36_CROSS_ARTIFACT_DRIFT')
    for key,n,expected,skips in [('module','r06b-go-test-counts.json',537,2),('jvm','r06b-jvm-test-counts.json',223,0)]:
        d=json.loads(contents[key][n]);identity(d,PINS[key][2],'xbs_source_sha')
        need(d['test_cases']==expected and d['failed_cases']==0 and d['skipped_cases']==skips and d.get('unexpected_skipped_cases',0)==0,'TEST_COUNTS')
        out['test_counts'][key]={'executed_or_skipped':expected,'failed':0,'skipped':skips,'passed':expected-skips}
    raw=contents['module'];final=json.loads(raw['r09f-final-ledger.json']);identity(final,PINS['module'][2],'xbs_source_sha')
    need(len(final['phase_ledger'])==5,'R09_PHASE_COUNT')
    for phase,row in final['phase_ledger'].items():
        blob=raw[row['filename']];d=json.loads(blob);identity(d,PINS['module'][2],'xbs_source_sha')
        need(row['sha256']==sha(blob) and row['size_bytes']==len(blob) and d['schema']==row['schema'],'R09_PHASE_DIGEST '+phase)
        for field in ('device_mutated','physical_root_qualified','release_qualified'):need(d[field] is False,'BACKEND_OVERCLAIM')
        for field,member in [('coverage_profile_sha256',{'r09a':'r09a-backend.coverprofile','r09c':'r09c-backend.coverprofile','r09d':'r09d-risk.coverprofile'}.get(phase))]:
            if member:need(d[field]==sha(raw[member]),'COVERAGE_PROFILE_DIGEST '+phase)
    need(final['failed_events']==final['skipped_events']==0 and final['root_executed'] is False and final['coverage']['profile_sha256']==sha(raw['r09d-risk.coverprofile']),'R09F_FINAL_SCOPE')
    out['backend']={'phase_ledger':final['phase_ledger'],'coverage':final['coverage'],'required_test_ids':final['required_test_ids'],'required_subtest_ids':final['required_subtest_ids'],'subtest_pass_events':final['subtest_pass_events'],'race_subtest_pass_events':final['race_subtest_pass_events'],'repeat_subtest_pass_events':final['repeat_subtest_pass_events']}
    return out

# All remain partial. A named executed method is evidence of its assertion, not
# proof of every original case condition. No user data or private log retained.
CASE_LINKS={
 'EM-002':('api36','apk_pair_receipt','App/test bytes match the hosted package/signer receipt; independent local signer and complete original fixture audit not executed.'),
 'EM-003':('api36','assertions','Five-tab navigation and real nonempty views; fresh offline actionable guidance not independently traced.'),
 'EM-004':('api36','assertions','Three actual navigation/view assertions; full screen and back-stack coverage missing.'),
 'EM-010':('api36','security','Hostile Tasker intent refusal executed; complete original hostile fixtures and result timing still open.'),
 'EM-014':('api36','process_death','External force-stop and two named stages observed; automatic replay absence expressly not qualified.'),
 'EM-017':('api36','screenshots','Requested light/dark images are byte-identical at each width; locale, RTL and accessibility unproved.'),
 'EM-019':('api36','tiles','15 exact tile methods plus real SystemUI callbacks; unavailable backend state full trace and physical OEM behavior not qualified.'),
 'EM-020':('api36','tiles','Durable pending blocks repeated tap across reload; actual rapid-tap and process recreation complete trace open.'),
 'EM-021':('api36','tiles','Separate confirmation and pending/recovery gates executed; unlock path not independently qualified.'),
 'EM-022':('api36','screenshots','Four image files verified; only status/navigation bands redacted, nonunique theme content and full privacy scan open.'),
 'EM-023':('api26','assertions','Four named lifecycle/smoke assertions; Tasker fallback and unsupported-call coverage incomplete.'),
 'EM-024':('api34','assertions','Five named Tasker/smoke assertions; foreground-service and API-specific exception coverage incomplete.'),
 'PF-001':('api36','startup','Seven cold-start observations and memory budget pass; approved original baseline regression comparison missing.'),
 'PF-007':('api36','screenshots','Dimensions and bytes verified; no clipping/overlap or theme uniqueness acceptance.'),
}

def inventory(root):
    from gx06_stack_gate import ORIGINAL,validate_original_inventory
    names=ORIGINAL[:3];raw=[(root/n).read_bytes() for n in names];l,m,t=map(json.loads,raw)
    validate_original_inventory(l,m,t)
    tasks=[{'id':task['id'],'phase':phase['id'],'historical_status':task['status'],'disposition':'OPEN_NO_INDEPENDENT_COMPLETE_ACCEPTANCE','criterion_sha256':sha(stable(task['acceptance']))} for phase in l['phases'] for task in phase['tasks']]
    cases=[]
    for c in m['cases']:
        rec={'id':c['id'],'lane':c['lane'],'historical_status':c['status'],'assertion_sha256':sha(stable(c['required_assertion'])),'accepted':False}
        if c['id'] in CASE_LINKS:
            a,field,gap=CASE_LINKS[c['id']];rec.update(disposition='PARTIAL_HOSTED_EVIDENCE',artifact=a,observation=field,remaining_gap=gap)
        else:rec.update(disposition='OPEN_NOT_REQUALIFIED',remaining_gap='Complete original assertion-to-execution and independent acceptance proof not established in this handback.')
        cases.append(rec)
    findings=[{'id':f['id'],'phase':f['primary_phase'],'historical_resolution':f['resolution'],'disposition':'OPEN_NO_INDEPENDENT_COMPLETE_CLOSURE'} for f in l['findings']]
    return {'protected_inputs':{n:sha(b) for n,b in zip(names,raw)},'tasks':tasks,'cases':cases,'findings':findings}

def document(root,observed):
    return {'schema':'xbs-agg-int01-historical-handback/v1','repository':'Mikeyphw/xbsctl','historical_pr':37,'historical_head':HEAD,'historical_tree':TREE,'historical_run_evidence':observed,'original_inventory':inventory(root),'new_candidate_ci_qualified':False,'original_cases_accepted':[],'physical_root_qualified':False,'release_qualified':False,'auto_merge_approved':False,'scope':'KNOWN_SUBSET_EXACT_ARCHIVES_AND_OPEN_INVENTORY'}

def validate_documents(root):
    p=Path(root)/FILE
    need(p.is_file() and not p.is_symlink() and p.stat().st_size<1_000_000,'HANDOFF_FILE')
    d=json.loads(p.read_bytes());obs=d['historical_run_evidence']
    need(sha(stable(obs))==OBSERVATION_SHA,'FROZEN_OBSERVATION_DRIFT')
    need(d==document(Path(root),obs),'HANDOFF_ORIGINAL_INVENTORY_OR_ACCEPTANCE_DRIFT')
    return {'historical_artifacts':len(obs['archives']),'partial_case_links':len(CASE_LINKS),'original_cases_accepted':0,'fresh_ci_qualified':False}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--repo',type=Path,default=ROOT);p.add_argument('--archives-dir',type=Path);a=p.parse_args()
    result=validate_documents(a.repo)
    if a.archives_dir:
        need(document(a.repo,replay(a.archives_dir))==json.loads((a.repo/FILE).read_bytes()),'ARCHIVE_REPLAY_DRIFT')
        result['archive_bytes_replayed']=True
    else:result['archive_bytes_replayed']=False
    print(json.dumps(result,sort_keys=True))
if __name__=='__main__':main()
