#!/usr/bin/env python3
"""GX02 independent offline fail-closed regressions and adversarial mutators."""
from __future__ import annotations
import copy
import datetime as dt
import io
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
import zipfile
import gx02_control as x

SHA='a'*40
STAMP='2026-11-15T00:00:00Z'
def selector(**kw):
    data={'repository':x.REPO,'head_sha':SHA,'branch':x.BRANCH,'earliest':STAMP}
    data.update(kw);return x.RunSelector(**data)
def run(**kw):
    data={'id':123456789,'run_attempt':1,'status':'completed','conclusion':'success',
          'event':'pull_request','head_branch':x.BRANCH,'head_sha':SHA,
          'workflow_id':x.WORKFLOW_ID,'created_at':'2026-11-15T00:00:03Z',
          'repository':{'full_name':x.REPO}}
    data.update(kw);return data
def jobs(gx02=True):
    steps=[{'name':x.GX01_STEP,'conclusion':'success'}]
    if gx02:steps.append({'name':x.GX02_STEP,'conclusion':'success'})
    return [{'name':'go-contract','status':'completed','conclusion':'success','steps':steps},
            {'name':'android-companion-jvm','status':'completed','conclusion':'success'},
            {'name':'android-companion-api36','status':'completed','conclusion':'success'},
            {'name':'android-companion-debug','status':'completed','conclusion':'skipped'}]
def workflow():
    return 'on:\n  pull_request:\npermissions:\n  contents: read\njobs:\n  go-contract:\n    steps:\n      - name: '+x.GX01_STEP+'\n        run: true\n      - name: XBS contracts\n        run: true\n'+('ref: ${{ github.event.pull_request.head.sha || github.sha }}\n'*4)

def counts(**kw):
    d={'schema':'xbs-r06b-hf03-test-counts/v1','xbs_source_sha':SHA,
       'github_run_id':'123456789','github_run_attempt':'1',
       'test_cases':10,'skipped_cases':0,'approved_skips':[],'failed_cases':0}
    d.update(kw);return x.canonical(d)
def fake_apk():
    return archive({'AndroidManifest.xml':b'M'*900,'classes.dex':b'dex\n'+b'X'*600})

def archive(files):
    b=io.BytesIO()
    with zipfile.ZipFile(b,'w') as z:
        for k,v in files.items():z.writestr(k,v)
    return b.getvalue()

class T01RunDiscovery(unittest.TestCase):
    def test_exact_run(self):self.assertEqual(x.select_run([run()],selector())['id'],123456789)
    def test_no_match_is_not_failed(self):self.assertIsNone(x.select_run([],selector()))
    def test_repository_mismatch(self):self.assertIsNone(x.select_run([run(repository={'full_name':'other/repo'})],selector()))
    def test_head_sha_mismatch(self):self.assertIsNone(x.select_run([run(head_sha='b'*40)],selector()))
    def test_branch_mismatch(self):self.assertIsNone(x.select_run([run(head_branch='main')],selector()))
    def test_workflow_id_mismatch(self):self.assertIsNone(x.select_run([run(workflow_id=22)],selector()))
    def test_event_mismatch(self):self.assertIsNone(x.select_run([run(event='push')],selector()))
    def test_older_run_not_accepted(self):self.assertIsNone(x.select_run([run(created_at='2026-11-14T23:00:00Z')],selector()))
    def test_run_attempt_required(self):self.assertIsNone(x.select_run([run(run_attempt=None)],selector()))
    def test_ambiguous_two_ids(self):
        with self.assertRaisesRegex(x.Refused,'AMBIGUOUS'):x.select_run([run(),run(id=99)],selector())
    def test_duplicate_run_id(self):
        with self.assertRaisesRegex(x.Refused,'DUPLICATE_RUN_ID'):x.select_run([run(),run()],selector())
    def test_remembered_run_only(self):
        self.assertIsNone(x.select_run([run(id=12)],selector(remembered_run=9)))
    def test_lower_attempt_refused(self):
        self.assertIsNone(x.select_run([run()],selector(remembered_run=123456789,remembered_attempt=2)))
    def test_utc_no_timezone(self):
        with self.assertRaisesRegex(x.Refused,'OFFSET'):x.utc('2026-11-15T00:00:00')
    def test_invalid_selector_branch(self):
        with self.assertRaisesRegex(x.Refused,'BRANCH'):x.select_run([],selector(branch='main'))
    def test_pending_eight_not_failed(self):self.assertEqual(x.check_state(8),'PENDING')
    def test_zero_only_advisory(self):self.assertIn('ADVISORY',x.check_state(0))
    def test_one_unknown_not_success(self):self.assertEqual(x.check_state(1),'UNQUALIFIED_OR_FAILED')
    def test_run_queued(self):self.assertEqual(x.classify_run(run(status='queued',conclusion=None)),'PENDING')
    def test_run_cancelled(self):self.assertEqual(x.classify_run(run(conclusion='cancelled')),'FAILED_CANCELLED')
    def test_run_success_pending_audit(self):self.assertIn('PENDING_JOB',x.classify_run(run()))

class T02JobGates(unittest.TestCase):
    def test_all_green(self):self.assertEqual(x.audit_jobs(jobs())['optional_job'],'skipped')
    def test_missing_job(self):
        with self.assertRaisesRegex(x.Refused,'MISSING'):x.audit_jobs(jobs()[:-2]+jobs()[-1:])
    def test_failed_job(self):
        j=jobs();j[1]['conclusion']='failure'
        with self.assertRaisesRegex(x.Refused,'NOT_SUCCESS'):x.audit_jobs(j)
    def test_skipped_required_job(self):
        j=jobs();j[2]['conclusion']='skipped'
        with self.assertRaises(x.Refused):x.audit_jobs(j)
    def test_optional_failure(self):
        j=jobs();j[-1]['conclusion']='failure'
        with self.assertRaises(x.Refused):x.audit_jobs(j)
    def test_optional_missing_is_permitted(self):self.assertIsNone(x.audit_jobs(jobs()[:-1])['optional_job'])
    def test_missing_actual_step(self):
        with self.assertRaises(x.Refused):x.audit_jobs(jobs(False))
    def test_duplicate_step(self):
        j=jobs();j[0]['steps'].append({'name':x.GX02_STEP,'conclusion':'success'})
        with self.assertRaises(x.Refused):x.audit_jobs(j)
    def test_unexpected_job(self):
        j=jobs()+[{'name':'unapproved','status':'completed','conclusion':'success'}]
        with self.assertRaises(x.Refused):x.audit_jobs(j)
    def test_duplicate_job(self):
        with self.assertRaises(x.Refused):x.audit_jobs(jobs()+[jobs()[0]])

class T03WorkflowAndManifest(unittest.TestCase):
    def test_workflow_insert_once(self):self.assertTrue(x.validate_workflow(x.update_workflow(workflow())))
    def test_workflow_no_double_insert(self):
        with self.assertRaisesRegex(x.Refused,'DUPLICATE'):x.update_workflow(x.update_workflow(workflow()))
    def test_workflow_refuses_missing_gx01(self):
        with self.assertRaises(x.Refused):x.update_workflow(workflow().replace(x.GX01_STEP,'other'))
    def test_workflow_refuses_broad_permissions(self):
        with self.assertRaises(x.Refused):x.update_workflow(workflow().replace('contents: read','contents: write'))
    def test_workflow_refuses_checkout_pin_change(self):
        with self.assertRaises(x.Refused):x.update_workflow(workflow().replace('ref: ${{ github.event.pull_request.head.sha || github.sha }}','ref: main'))
    def test_workflow_missing_test(self):
        with self.assertRaises(x.Refused):x.validate_workflow(x.update_workflow(workflow()).replace('gx02_ci_verify.py','nonexistent.py'))
    def test_manifest_hashes(self):
        c={'docs/test.md':b'abc','scripts/ci/g.py':b'123','scripts/ci/a.py':b'abc',
           '.github/workflows/devtool-xbs-ci.yml':b'workflow changed','docs/s.md':b'abc'}
        original={'.github/workflows/devtool-xbs-ci.yml':b'old workflow'}
        m=x.prepare_native_manifest(c,original)
        self.assertTrue(x.validate_native_manifest(m,c,original))
        self.assertEqual([r for r in m['inventory']['entries'] if r['action']=='modify'][0]['source_sha256'],x.sha256(b'old workflow'))
    def test_manifest_source_corruption(self):
        c={str(i):b'a' for i in range(5)};m=x.prepare_native_manifest(c,{'0':b'original'})
        m['inventory']['entries'][0]['source_sha256']='0'*64
        with self.assertRaises(x.Refused):x.validate_native_manifest(m,c,{'0':b'original'})
    def test_path_traversal(self):
        for p in ('../evil','/sdcard/Download/file','a/../../b','a\\b'):
            with self.subTest(path=p):self.assertFalse(x.is_safe_rel(p))
    def test_zip_duplicate(self):
        b=io.BytesIO()
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter('ignore')
            with zipfile.ZipFile(b,'w') as z:z.writestr('a',b'1');z.writestr('a',b'2')
        with self.assertRaises(x.Refused):x.inspect_zip_bytes(b.getvalue())
    def test_zip_path_traversal(self):
        with self.assertRaises(x.Refused):x.inspect_zip_bytes(archive({'../x':b'danger'}))
    def test_zip_crc_corrupt(self):
        data=archive({'a.txt':b'ABCD'})
        data=data.replace(b'ABCD',b'XBCD',1)
        with self.assertRaises(Exception):x.inspect_zip_bytes(data)
    def test_zip_member_limit(self):
        with self.assertRaises(x.Refused):x.inspect_zip_bytes(archive({'a':b'x'*500}),max_size=100)
    def test_no_command_gradle(self):
        with self.assertRaises(x.Refused):x.reject_excluded_commands([['gradle',':app:assembleDebug']])
    def test_no_command_adb(self):
        with self.assertRaises(x.Refused):x.reject_excluded_commands([['adb','shell','id']])
    def test_safe_git_devtool_and_gh(self):self.assertTrue(x.reject_excluded_commands([['git','status'],['devtool','overlay','check'],['gh','pr','view']]))
    def test_json_cli_flag_is_forbidden_in_all_commands(self):
        for command in (['devtool','status','--json'],['gh','pr','view','27','--json'],
                        ['devtool','overlay','check','a.zip','--json']):
            with self.subTest(command=command):
                with self.assertRaisesRegex(x.Refused,'FORBIDDEN_JSON_CLI_FLAG'):
                    x.reject_excluded_commands([command])
    def test_reviewed_readable_devtool_preflight(self):
        pre=['devtool','--no-bootstrap-downloads','--no-auto-chroot','--no-hud','-r','/tmp/disposable']
        calls=x.readable_devtool_preflight(pre,'/tmp/bundle.zip')
        self.assertEqual([label for label,_ in calls],['STATUS','MANIFEST_CHECK','OVERLAY_CHECK','OVERLAY_PLAN'])
        self.assertEqual([argv[len(pre):] for _,argv in calls],
             [['status'],['validate-overlay','/tmp/bundle.zip'],
              ['overlay','check','/tmp/bundle.zip'],['overlay','plan','/tmp/bundle.zip']])
        self.assertTrue(x.reject_excluded_commands([cmd for _,cmd in calls]))
    def test_readable_preflight_rejects_unreviewed_inputs(self):
        for prefix,artifact in ((['bash'],'/tmp/bundle.zip'),(['devtool'],'/tmp/bundle.zip'),
                                (['devtool','-r','/tmp/checkout'],'/tmp/bundle.tar')):
            with self.subTest(prefix=prefix,artifact=artifact):
                with self.assertRaises(x.Refused):x.readable_devtool_preflight(prefix,artifact)

class T04ActualArtifactBoundaries(unittest.TestCase):
    def test_api36_pair(self):
        app=fake_apk()
        data={'SOURCE_SHA':SHA.encode()+b'\n','SHA256SUMS':(x.sha256(app)+'  xbs-companion-r05c-api36-debug.apk\n').encode(),
              'xbs-companion-r05c-api36-debug.apk':app}
        self.assertEqual(x.audit_apk_pair(data,SHA,123456789,1)['device'],'EMULATOR_ONLY')
    def test_api36_bad_sha(self):
        app=fake_apk()
        data={'SOURCE_SHA':b'b'*40,'SHA256SUMS':(x.sha256(app)+'  xbs-companion-r05c-api36-debug.apk').encode(),
              'xbs-companion-r05c-api36-debug.apk':app}
        with self.assertRaises(x.Refused):x.audit_apk_pair(data,SHA,123456789,1)
    def test_api36_mismatched_bytes(self):
        app=fake_apk()
        data={'SOURCE_SHA':SHA.encode(),'SHA256SUMS':(x.sha256(b'Q'*2048)+'  xbs-companion-r05c-api36-debug.apk').encode(),
              'xbs-companion-r05c-api36-debug.apk':app}
        with self.assertRaises(x.Refused):x.audit_apk_pair(data,SHA,123456789,1)
    def test_test_receipt_cases(self):self.assertEqual(x.audit_count_receipt(counts(),SHA,123456789,1)['case_count'],10)
    def test_go_expected_skip_explicit(self):
        self.assertEqual(x.audit_count_receipt(counts(skipped_cases=1,approved_skips=[{'test':'expected'}]),SHA,123456789,1,allow_skips=2)['skipped_cases'],1)
    def test_test_receipt_zero(self):
        with self.assertRaises(x.Refused):x.audit_count_receipt(counts(test_cases=0),SHA,123456789,1)
    def test_test_receipt_skip_unexpected(self):
        with self.assertRaises(x.Refused):x.audit_count_receipt(counts(skipped_cases=1,approved_skips=[{'test':'expected'}]),SHA,123456789,1)
    def test_test_receipt_attempt_wrong(self):
        with self.assertRaises(x.Refused):x.audit_count_receipt(counts(github_run_attempt='2'),SHA,123456789,1)
    def test_test_receipt_failed(self):
        with self.assertRaises(x.Refused):x.audit_count_receipt(counts(failed_cases=1),SHA,123456789,1)
    def test_coverage_valid(self):
        data=b'mode: set\n'+b'foo.go:1.1,3.4 2 1\nfoo.go:4.1,5.6 3 0\nfoo.go:6.1,9.6 4 1\n'*3
        self.assertEqual(x.audit_coverage(data)['statements'],27)
    def test_coverage_bad_header(self):
        with self.assertRaises(x.Refused):x.audit_coverage(b'bad'+b'a'*200)
    def test_coverage_bad_lines(self):
        with self.assertRaises(x.Refused):x.audit_coverage(b'mode: set\n'+b'invalid_line\n'*15)
    def test_coverage_wrong_hash(self):
        with self.assertRaises(x.Refused):x.audit_coverage(b'mode: set\n'+b'f.go:1.1,2.3 1 1\n'*10,expected_sha256='a'*64)

class T05DurableRecovery(unittest.TestCase):
    def test_secure_journal_and_resume(self):
        with tempfile.TemporaryDirectory() as t:
            j=x.Journal(Path(t)/'delivery.json');d=j.initial();j.save(d)
            self.assertEqual(j.load(),d)
            self.assertEqual(stat.S_IMODE(j.path.stat().st_mode),0o600)
            d=x.Journal.advance(d,'WORKTREE');j.save(d)
            self.assertEqual(j.load()['state'],'WORKTREE')
    def test_symlink_journal_refused(self):
        with tempfile.TemporaryDirectory() as t:
            p=Path(t)/'delivery.json';p.symlink_to(Path(t)/'other')
            with self.assertRaises(x.Refused):x.Journal(p).load()
    def test_group_readable_refused(self):
        with tempfile.TemporaryDirectory() as t:
            j=x.Journal(Path(t)/'delivery.json');j.save(j.initial());j.path.chmod(0o644)
            with self.assertRaises(x.Refused):j.load()
    def test_foreign_journal_sha(self):
        with tempfile.TemporaryDirectory() as t:
            j=x.Journal(Path(t)/'delivery.json');d=j.initial();d['base_sha']='f'*40
            with self.assertRaises(x.Refused):j.save(d)
    def test_stage_regression(self):
        d=x.Journal(Path('/tmp/none')).initial();d['state']='COMMITTED'
        with self.assertRaises(x.Refused):x.Journal.advance(d,'WORKTREE')
    def test_hosted_failed_resume(self):
        d=x.Journal(Path('/tmp/none')).initial();d['state']='HOSTED_FAILED'
        self.assertEqual(x.Journal.advance(d,'HOSTED_PENDING')['state'],'HOSTED_PENDING')
    def test_pr_reuse(self):
        p={'number':27,'state':'open','head':{'ref':x.BRANCH,'sha':SHA},'base':{'ref':x.BASE_BRANCH}}
        self.assertEqual(x.safe_pr_list([p],SHA)['number'],27)
    def test_pr_drift(self):
        p={'number':27,'state':'open','head':{'ref':x.BRANCH,'sha':'b'*40},'base':{'ref':x.BASE_BRANCH}}
        with self.assertRaises(x.Refused):x.safe_pr_list([p],SHA)
    def test_pr_duplicate_refused(self):
        p={'number':27,'state':'open','head':{'ref':x.BRANCH,'sha':SHA},'base':{'ref':x.BASE_BRANCH}}
        with self.assertRaises(x.Refused):x.safe_pr_list([p,p],SHA)
    def test_pr_missing_not_created(self):self.assertIsNone(x.safe_pr_list([],SHA))
    def test_token_redaction(self):self.assertNotIn('MYSECRET',x.redact('token=MYSECRET'))
    def test_json_not_object(self):
        with self.assertRaises(x.Refused):x.json_object('[]')

if __name__=='__main__':unittest.main(verbosity=2)
