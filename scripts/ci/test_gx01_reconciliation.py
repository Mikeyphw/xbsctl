#!/usr/bin/env python3
"""Named GX01 adversarial cases. Full original matrix must remain UNMAPPED, never false-green."""
import copy, json, pathlib, sys, unittest
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parent))
from gx01_core import (Refusal,jload,original_invariants,validate_observation,
                       reconcile,assert_reconciled,original_tasks,case_index)

ROOT=pathlib.Path(__file__).resolve().parents[2]
H=ROOT/'docs/engineering/gx01_handoff'

def fixture():
    h={str(p.relative_to(H)):p.read_bytes() for p in H.rglob('*') if p.is_file()}
    obs=jload((ROOT/'docs/engineering/XBS_GX01_GITHUB_OBSERVATION.json').read_bytes())
    links=jload((ROOT/'docs/engineering/XBS_GX01_METHOD_CANDIDATES.json').read_bytes())
    live=jload((ROOT/'docs/engineering/XBS_IMPLEMENTATION_LEDGER.json').read_bytes())
    cases=jload((ROOT/'docs/engineering/XBS_TEST_MATRIX.json').read_bytes())
    old,_,oldm,_,_,_=original_invariants(h)
    return h,obs,links,live,cases,old,oldm

class GX01ReconciliationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.h,cls.o,cls.c,cls.live,cls.m,cls.old,cls.oldm=fixture()
    def assertRefuses(self,func):
        with self.assertRaises(Refusal):func()
    def test_01_positive_source_pin_original_ids(self):
        assert_reconciled(self.live,self.m,self.old,self.oldm,self.o)
    def test_02_handoff_manifest_missing(self):
        h=dict(self.h);h.pop('README_FIRST.md');self.assertRefuses(lambda:original_invariants(h))
    def test_03_handoff_manifest_hash_drift(self):
        h=dict(self.h);h['README_FIRST.md']=b'altered';self.assertRefuses(lambda:original_invariants(h))
    def test_04_original_case_duplicate_or_deleted(self):
        m=copy.deepcopy(self.m);m['cases'].pop();self.assertRefuses(lambda:assert_reconciled(self.live,m,self.old,self.oldm,self.o))
    def test_05_original_task_removed(self):
        l=copy.deepcopy(self.live);l['phases'][0]['tasks'].pop();self.assertRefuses(lambda:assert_reconciled(l,self.m,self.old,self.oldm,self.o))
    def test_06_task_original_acceptance_changed(self):
        l=copy.deepcopy(self.live);l['phases'][0]['tasks'][0]['acceptance']='SHORTCUT';self.assertRefuses(lambda:assert_reconciled(l,self.m,self.old,self.oldm,self.o))
    def test_07_original_case_assertion_changed(self):
        m=copy.deepcopy(self.m);m['cases'][0]['required_assertion']='WEAK';self.assertRefuses(lambda:assert_reconciled(self.live,m,self.old,self.oldm,self.o))
    def test_08_fake_task_accepted(self):
        l=copy.deepcopy(self.live);l['phases'][0]['tasks'][0]['status']='ACCEPTED';self.assertRefuses(lambda:assert_reconciled(l,self.m,self.old,self.oldm,self.o))
    def test_09_fake_case_pass(self):
        m=copy.deepcopy(self.m);m['cases'][0]['status']='PASS_AT_EXACT_SHA';self.assertRefuses(lambda:assert_reconciled(self.live,m,self.old,self.oldm,self.o))
    def test_10_partial_candidate_not_accepted(self):
        m=copy.deepcopy(self.m);c=next(x for x in m['cases'] if x['id']=='BE-014');c['gx01_reconciliation']['mapping_status']='PASS';self.assertRefuses(lambda:assert_reconciled(self.live,m,self.old,self.oldm,self.o))
    def test_11_fake_candidate_run_id(self):
        m=copy.deepcopy(self.m);c=next(x for x in m['cases'] if x['id']=='BE-014');c['gx01_reconciliation']['candidate_named_executions'][0]['run_id']=1;self.assertRefuses(lambda:assert_reconciled(self.live,m,self.old,self.oldm,self.o))
    def test_12_r09f_source_drift(self):
        o=copy.deepcopy(self.o);o['stack_head_sha']='0'*40;self.assertRefuses(lambda:validate_observation(o,jload(self.h['XBS_PROGRESS_SNAPSHOT_20261114.json']),self.c))
    def test_13_run_number_drift(self):
        o=copy.deepcopy(self.o);o['history'][9]['run_id']=0;self.assertRefuses(lambda:validate_observation(o,jload(self.h['XBS_PROGRESS_SNAPSHOT_20261114.json']),self.c))
    def test_14_required_go_job_skipped(self):
        o=copy.deepcopy(self.o);o['latest_run']['jobs'][0]['conclusion']='skipped';self.assertRefuses(lambda:validate_observation(o,jload(self.h['XBS_PROGRESS_SNAPSHOT_20261114.json']),self.c))
    def test_15_api36_job_failure(self):
        o=copy.deepcopy(self.o);o['latest_run']['jobs'][2]['conclusion']='failure';self.assertRefuses(lambda:validate_observation(o,jload(self.h['XBS_PROGRESS_SNAPSHOT_20261114.json']),self.c))
    def test_16_optional_debug_skip_not_pass(self):
        o=copy.deepcopy(self.o);o['latest_run']['jobs'][3]['conclusion']='success';self.assertRefuses(lambda:validate_observation(o,jload(self.h['XBS_PROGRESS_SNAPSHOT_20261114.json']),self.c))
    def test_17_false_artifact_digest(self):
        o=copy.deepcopy(self.o);o['latest_run']['artifacts'][0]['reported_digest_sha256']='not-hash';self.assertRefuses(lambda:validate_observation(o,jload(self.h['XBS_PROGRESS_SNAPSHOT_20261114.json']),self.c))
    def test_18_named_method_source_blob_drift(self):
        c=copy.deepcopy(self.c);c['case_links'][0]['source_blob_sha']='0'*40;self.assertRefuses(lambda:validate_observation(self.o,jload(self.h['XBS_PROGRESS_SNAPSHOT_20261114.json']),c))
    def test_19_case_assertion_false_qualified(self):
        m=copy.deepcopy(self.m);m['cases'][0]['gx01_reconciliation']['assertion_qualified']=True;self.assertRefuses(lambda:assert_reconciled(self.live,m,self.old,self.oldm,self.o))
    def test_20_audit_finding_false_closed(self):
        l=copy.deepcopy(self.live);l['findings'][0]['gx01_review']['classification']='FIXED';self.assertRefuses(lambda:assert_reconciled(l,self.m,self.old,self.oldm,self.o))
    def test_21_original_phase_gate_false_accept(self):
        l=copy.deepcopy(self.live);l['phases'][0]['original_gate_status']='ACCEPTED';self.assertRefuses(lambda:assert_reconciled(l,self.m,self.old,self.oldm,self.o))
    def test_22_missing_task_id_in_live_rejected(self):
        h,o,c,live,m,old,oldm=self.h,self.o,self.c,copy.deepcopy(self.old),copy.deepcopy(self.oldm),self.old,self.oldm
        live['phases'][0]['tasks'].pop()
        self.assertRefuses(lambda:reconcile(h,o,c,json.dumps(live).encode(),json.dumps(m).encode()))
    def test_23_live_acceptance_criteria_refusal(self):
        live=copy.deepcopy(self.old);live['phases'][0]['tasks'][0]['title']='relabel'
        self.assertRefuses(lambda:reconcile(self.h,self.o,self.c,json.dumps(live).encode(),json.dumps(self.oldm).encode()))
    def test_24_live_accepted_case_conflict_refused(self):
        m=copy.deepcopy(self.oldm);m['cases'][0]['status']='PASS_AT_EXACT_SHA'
        self.assertRefuses(lambda:reconcile(self.h,self.o,self.c,json.dumps(self.old).encode(),json.dumps(m).encode()))
    def test_25_live_accepted_task_conflict_refused(self):
        l=copy.deepcopy(self.old);l['phases'][0]['tasks'][0]['status']='ACCEPTED'
        self.assertRefuses(lambda:reconcile(self.h,self.o,self.c,json.dumps(l).encode(),json.dumps(self.oldm).encode()))
    def test_26_true_reconcile_preserves_178_131_26_and_history(self):
        l,m=reconcile(self.h,self.o,self.c,json.dumps(self.old).encode(),json.dumps(self.oldm).encode())
        self.assertEqual(len(original_tasks(l)),178);self.assertEqual(len(case_index(m)),131);self.assertEqual(len(l['findings']),26)
        self.assertEqual(sum(c['status']=='NOT_RUN' for c in m['cases']),131)
    def test_27_no_unmapped_case_is_promoted_by_simulation(self):
        l,m=reconcile(self.h,self.o,self.c,json.dumps(self.old).encode(),json.dumps(self.oldm).encode())
        for c in m['cases']:
            self.assertFalse(c['gx01_reconciliation']['assertion_qualified'])
    def test_28_candidate_wrong_workflow_step_refused(self):
        c=copy.deepcopy(self.c);c['case_links'][0]['verdict']='PASS_AT_EXACT_SHA'
        self.assertRefuses(lambda:validate_observation(self.o,jload(self.h['XBS_PROGRESS_SNAPSHOT_20261114.json']),c))
    def test_29_cumulative_head_not_introducing_commit(self):
        l,m=reconcile(self.h,self.o,self.c,json.dumps(self.old).encode(),json.dumps(self.oldm).encode())
        for t in original_tasks(l).values():
            if t['implementation_sha'] is None:
                self.assertIn('INTRODUCING_SHA_UNMAPPED',t['gx01_reconciliation']['blockers'])
    def test_30_legacy_phase31_no_release_credit(self):
        import gx01_core
        l,m=reconcile(self.h,self.o,self.c,json.dumps(self.old).encode(),json.dumps(self.oldm).encode())
        out=gx01_core.render(l,m,self.h,self.o,self.c)
        legacy=json.loads(out['docs/engineering/XBS_GX01_ARCHIVED_LEGACY_EXCLUSIONS.json'])
        self.assertEqual(legacy['phase31']['credit_for_original_cases'],0)
        self.assertEqual(legacy['phase31']['credit_for_signed_release'],0)
    def test_31_finding_ids_exact(self):
        l=copy.deepcopy(self.live);l['findings'][0]['id']='CODE-FAKE';self.assertRefuses(lambda:assert_reconciled(l,self.m,self.old,self.oldm,self.o))
    def test_32_reconciler_does_not_mark_physical_root(self):
        l,m=reconcile(self.h,self.o,self.c,json.dumps(self.old).encode(),json.dumps(self.oldm).encode())
        import gx01_core
        rec=json.loads(gx01_core.render(l,m,self.h,self.o,self.c)['docs/engineering/XBS_GX01_RECONCILIATION_RECEIPT.json'])
        self.assertFalse(rec['physical_root']);self.assertFalse(rec['signed_release'])

if __name__=='__main__':
    unittest.main(verbosity=2)
