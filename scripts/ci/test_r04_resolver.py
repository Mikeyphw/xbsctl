"""R04 hosted evidence, explicit no-root and actual shell fixture tests."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import unittest
from unittest import mock

MOD=Path(__file__).with_name('r04_resolve_hosted_module.py')
spec=importlib.util.spec_from_file_location('xbs_r04_resolver',MOD)
r=importlib.util.module_from_spec(spec)
spec.loader.exec_module(r)

SHA='abcdef0123456789abcdef0123456789abcdef01'
RUN=321;ATTEMPT=2
ART=f'xbs-android-module-{RUN}-{ATTEMPT}'
STEPS=['Go unit tests','Go vet','XBS contracts','Source-bound final seal','Android module candidate','Module checksum']

def mock_gh_factory(*,bad_checksum=False,extra=False,missing_job=False,bad_step=False,bad_sha=False,bad_checkout=False,bad_workflow=False,dirty_checkout=False):
    calls=[]
    def cmd(argv,cwd=None):
        calls.append(list(argv))
        if argv[:2]==['git','rev-parse']:return ('0'*40 if bad_checkout else SHA)+'\n'
        if argv[:2]==['git','status']:return ' M internal/module/r04_safety.go\n' if dirty_checkout else ''
        if argv[:2]==['gh','api']:
            api=argv[2]
            if api.endswith(f'actions/runs/{RUN}'):
                return json.dumps({'id':RUN,'head_sha':'0'*40 if bad_sha else SHA,'conclusion':'success','status':'completed','run_attempt':ATTEMPT,'repository':{'full_name':r.REPO}})
            if api.endswith('/jobs?per_page=100'):
                steps=[{'name':s,'conclusion':'failure' if bad_step and s=='Module checksum' else 'success'} for s in STEPS]
                return json.dumps({'jobs':[] if missing_job else [{'name':'go-contract','conclusion':'success','run_attempt':ATTEMPT,'steps':steps}]})
            if api.endswith('/artifacts?per_page=100'):
                return json.dumps({'artifacts':[{'name':ART,'id':24,'expired':False,'size_in_bytes':400,'workflow_run':{'head_sha':SHA,'id':RUN+1 if bad_workflow else RUN}}]})
            raise AssertionError(api)
        if argv[:3]==['gh','run','download']:
            out=Path(argv[argv.index('--dir')+1]);b=b'PK\x03\x04fixture' # safety of contents is covered by local Go module verification
            (out/'XtremeBS-go.zip').write_bytes(b)
            digest=hashlib.sha256(b).hexdigest()
            if bad_checksum:digest='0'*64
            (out/'SHA256SUMS').write_text(digest+'  XtremeBS-go.zip\n')
            if extra:(out/'unexpected').write_bytes(b'x')
            return ''
        if argv[:4]==['go','run','./cmd/xbsrepo','verify-module-zip']:
            assert cwd is not None
            return 'R04 module ZIP validated'
        raise AssertionError(argv)
    return cmd,calls

class HostedR04Tests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(prefix='r04-hosted-test-')
        self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
        self.checkout=self.root/'repo';self.checkout.mkdir();(self.checkout/'go.mod').write_text('module github.com/Mikeyphw/xbsctl\n')
        self.out=self.root/'out';self.out.mkdir()
    def exercise(self,**kw):
        cmd,calls=mock_gh_factory(**kw)
        with mock.patch.object(r,'execute',cmd):
            result=r.resolve(RUN,SHA,self.out,self.checkout)
        return result,calls
    def test_verified_candidate_is_published_without_root(self):
        result,calls=self.exercise()
        self.assertEqual(result['status'],'HOSTED_ARTIFACT_CHECKSUM_AND_LAYOUT_VERIFIED_NOT_DEVICE_ACCEPTANCE')
        self.assertTrue(Path(result['zip']).is_file());self.assertEqual(json.loads(Path(result['receipt']).read_text())['commit'],SHA)
        self.assertTrue(all(c[0] in ('go','gh','git') for c in calls))
        self.assertFalse(any(s in str(c) for c in calls for s in ('su -c','ksud module install','magisk --install-module','adb shell')))
    def test_latest_source_exact_skips_unqualified_and_selects_newest_qualified(self):
        cmd,calls=mock_gh_factory()
        def execute(argv,cwd=None):
            if argv[:2]==['gh','api'] and '/actions/runs?' in argv[2]:
                listing={'workflow_runs':[{'id':RUN+1,'head_sha':SHA,'status':'completed','conclusion':'success','repository':{'full_name':r.REPO},'created_at':'2026-11-13T02:00:00Z'}, {'id':RUN,'head_sha':SHA,'status':'completed','conclusion':'success','repository':{'full_name':r.REPO},'created_at':'2026-11-13T01:00:00Z'}]}
                return json.dumps(listing)
            if argv[:2]==['gh','api'] and argv[2].endswith(f'/actions/runs/{RUN+1}'):
                return json.dumps({'id':RUN+1,'head_sha':SHA,'status':'completed','conclusion':'failure'})
            return cmd(argv,cwd=cwd)
        with mock.patch.object(r,'execute',execute):result=r.resolve_latest(SHA,self.out,self.checkout)
        self.assertEqual(result['run_id'],RUN)
        self.assertEqual(result['discovery'],'LATEST_SUCCESSFUL_SOURCE_EXACT_QUALIFIED_RUN')
    def test_latest_refuses_missing_successful_runs(self):
        def execute(argv,cwd=None):
            if argv[:2]==['gh','api'] and '/actions/runs?' in argv[2]:return json.dumps({'workflow_runs':[]})
            raise AssertionError(argv)
        with mock.patch.object(r,'execute',execute):
            with self.assertRaisesRegex(r.Refusal,'no successful'):r.resolve_latest(SHA,self.out,self.checkout)
    def test_latest_rejects_invalid_source_sha_before_api(self):
        with self.assertRaisesRegex(r.Refusal,'exact source SHA'):r.resolve_latest('bad',self.out,self.checkout)

    def test_wrong_local_checkout_refuses(self):
        with self.assertRaisesRegex(r.Refusal,'checkout HEAD'):self.exercise(bad_checkout=True)
        self.assertEqual(list(self.out.iterdir()),[])
    def test_dirty_local_verifier_refuses(self):
        with self.assertRaisesRegex(r.Refusal,'local Go verifier source'):self.exercise(dirty_checkout=True)
    def test_artifact_wrong_workflow_refuses(self):
        with self.assertRaisesRegex(r.Refusal,'associated with exact workflow'):self.exercise(bad_workflow=True)
    def test_source_sha_drift_refuses(self):
        with self.assertRaisesRegex(r.Refusal,'exact-source'):self.exercise(bad_sha=True)
        self.assertEqual(list(self.out.iterdir()),[])
    def test_job_missing_refuses(self):
        with self.assertRaisesRegex(r.Refusal,'go-contract'):self.exercise(missing_job=True)
    def test_failed_checksum_step_refuses(self):
        with self.assertRaisesRegex(r.Refusal,'steps missing'):self.exercise(bad_step=True)
    def test_bad_checksum_refuses_and_no_artifact(self):
        with self.assertRaisesRegex(r.Refusal,'checksum mismatch'):self.exercise(bad_checksum=True)
        self.assertEqual(list(self.out.iterdir()),[])
    def test_extra_member_refuses(self):
        with self.assertRaisesRegex(r.Refusal,'unexpected'):self.exercise(extra=True)
    def test_no_overwrite_on_duplicate_resolution(self):
        result,_=self.exercise();orig=Path(result['zip']).read_bytes()
        with self.assertRaisesRegex(r.Refusal,'already exists'):self.exercise()
        self.assertEqual(Path(result['zip']).read_bytes(),orig)
    def test_invalid_sha_refuses_before_gh(self):
        with self.assertRaisesRegex(r.Refusal,'explicit run-id'):r.resolve(RUN,'not-a-sha',self.out,self.checkout)
    def test_symlinked_output_refuses(self):
        alias=self.root/'alias';alias.symlink_to(self.out,target_is_directory=True)
        with self.assertRaisesRegex(r.Refusal,'symlinked'):r.resolve(RUN,SHA,alias,self.checkout)
    def test_boot_service_orders_init_validate_daemon_and_fail_closes(self):
        source=Path(__file__).parent.parent.parent/'module'/'service.sh'
        if not source.exists():self.fail('R04 service.sh test fixture missing')
        script=source.read_text()
        self.assertEqual(script.count('"/sdcard/Android"'),1,'unexpected boot storage gate drift')
        root=self.root/'servicemod';(root/'system'/'bin').mkdir(parents=True)
        shared=self.root/'Android';shared.mkdir()
        staged=root/'service.test.sh';staged.write_text(script.replace('"/sdcard/Android"',repr(str(shared))))
        fakebin=self.root/'fakebin';fakebin.mkdir()
        (fakebin/'getprop').write_text('#!/bin/sh\necho 1\n')
        (fakebin/'getprop').chmod(0o755)
        ctl=root/'system'/'bin'/'xbs'
        ctl.write_text('#!/bin/sh\nprintf "%s\\n" "$1" >> "$XBS_MOCK_ORDER"\n[ "$XBS_MOCK_FAIL" != "$1" ]\n')
        ctl.chmod(0o755)
        daemon=root/'system'/'bin'/'XtremeBSd'
        daemon.write_text('#!/bin/sh\nprintf "daemon\\n" >> "$XBS_MOCK_ORDER"\n')
        daemon.chmod(0o755)
        order=self.root/'order.log'
        env={**os.environ,'PATH':str(fakebin)+os.pathsep+os.environ.get('PATH',''), 'XBS_MOCK_ORDER':str(order)}
        def run(mode):
            order.write_text('')
            e={**env,'XBS_MOCK_FAIL':mode}
            p=subprocess.run(['sh',str(staged)],env=e,capture_output=True,text=True,timeout=10)
            for _ in range(20):
                events=order.read_text().splitlines()
                if p.returncode or len(events)>=3:break
                time.sleep(.01)
            return p.returncode,order.read_text().splitlines()
        rc,events=run('none')
        self.assertEqual(rc,0)
        self.assertEqual(events,['init','validate','daemon'])
        for failure,expected in [('init',['init']),('validate',['init','validate'])]:
            rc,events=run(failure)
            self.assertNotEqual(rc,0)
            self.assertEqual(events,expected,'daemon ran despite failed '+failure)
    def test_shell_customizer_accepts_verified_checksum(self):
        source=Path(__file__).parent.parent.parent/'module'/'customize.sh'
        if not source.exists():self.skipTest('standalone fixture has no module shell')
        mod=self.root/'module';(mod/'runtime'/'arm64-v8a').mkdir(parents=True)
        lines=[]
        for name in ('xbs','XBSctl','XtremeBSd'):
            data=(name+' ELF').encode();(mod/'runtime'/'arm64-v8a'/name).write_bytes(data)
            lines.append(hashlib.sha256(data).hexdigest()+'  runtime/arm64-v8a/'+name)
        (mod/'runtime'/'arm64-v8a'/'runtime.sha256').write_text('\n'.join(lines)+'\n')
        invocation='ui_print(){ :;}; abort(){ echo "REFUSED:$*" >&2; exit 77;}; set_perm_recursive(){ :;}; set_perm(){ :;}; . "$1"'
        p=subprocess.run(['sh','-c',invocation,'sh',str(source)],env={**os.environ,'MODPATH':str(mod)},capture_output=True,text=True)
        self.assertEqual(p.returncode,0,p.stderr)
        (mod/'runtime'/'arm64-v8a'/'xbs').write_bytes(b'tampered')
        p=subprocess.run(['sh','-c',invocation,'sh',str(source)],env={**os.environ,'MODPATH':str(mod)},capture_output=True,text=True)
        self.assertNotEqual(p.returncode,0)
        self.assertIn('runtime checksum mismatch',p.stderr)

if __name__=='__main__':unittest.main()
