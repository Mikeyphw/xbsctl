"""Independent adverse checks of the committed historical handback."""
from pathlib import Path
import copy
import json
import tempfile
import unittest
from unittest.mock import patch
import aggregate_handback as a

class HandbackTests(unittest.TestCase):
    def setUp(self):
        self.doc=json.loads((a.ROOT/a.FILE).read_bytes())
    def test_actual_inventory_and_observations(self):
        self.assertEqual(a.validate_documents(a.ROOT),{'historical_artifacts':7,'partial_case_links':14,'original_cases_accepted':0,'fresh_ci_qualified':False})
        self.assertEqual([len(self.doc['original_inventory'][k]) for k in ('tasks','cases','findings')],[178,131,26])
    def tamper(self,change):
        d=copy.deepcopy(self.doc);change(d)
        with tempfile.TemporaryDirectory() as t:
            root=Path(t);p=root/a.FILE;p.parent.mkdir(parents=True);p.write_text(json.dumps(d))
            for n in self.doc['original_inventory']['protected_inputs']:
                (root/n).write_bytes((a.ROOT/n).read_bytes())
            with self.assertRaises(a.Refused):a.validate_documents(root)
    def test_missing_case(self):self.tamper(lambda d:d['original_inventory']['cases'].pop())
    def test_accepted_case(self):self.tamper(lambda d:d['original_inventory']['cases'][0].update(accepted=True))
    def test_false_release(self):self.tamper(lambda d:d.update(release_qualified=True))
    def test_false_fresh_ci(self):self.tamper(lambda d:d.update(new_candidate_ci_qualified=True))
    def test_run_drift(self):self.tamper(lambda d:d['historical_run_evidence']['archives']['api36'].update(run_id=1))
    def test_bad_archive(self):
        with tempfile.TemporaryDirectory() as t:
            p=Path(t)/'bad.zip';p.write_bytes(b'invalid')
            with self.assertRaises(a.Refused):a.archive(p,'jvm')
    def test_same_size_bad_digest(self):
        with tempfile.TemporaryDirectory() as t:
            p=Path(t)/'bad.zip';p.write_bytes(b'0'*a.PINS['jvm'][4])
            with self.assertRaisesRegex(a.Refused,'PROVIDER_DIGEST'):a.archive(p,'jvm')
    def test_named_receipt_negative(self):
        for change in ({'matched':{'Class':['one','one']}},{'skipped_cases':1},{'failed_cases':1}):
            r={'matched':{'Class':['one']},'required_testcases':1,'total_testcases':1,'failed_cases':0,'skipped_cases':0,'testcase_identifiers_verified':True};r.update(change)
            with self.assertRaises(a.Refused):a.assertions(r,{'Class':{'one'}})
    def test_theme_gap_not_promoted(self):
        self.assertEqual(self.doc['historical_run_evidence']['android']['api36']['distinct_theme_content_by_width'],{'360':False,'393':False})
        self.assertFalse(self.doc['historical_run_evidence']['android']['api36']['process_death']['automatic_replay_qualified'])
    def test_wrong_event_untrusted_gx05(self):
        import gx05_preflight as g
        from test_gx05_preflight import sample
        x=sample();x['identity']['event']='push'
        with self.assertRaises(g.Refused):g.evidence(**x)
    def test_main_gx05_verified_context(self):
        import gx05_preflight as g
        from test_gx05_preflight import sample
        x=sample();x['identity']['event']='push'
        with patch.dict('os.environ',{'GITHUB_EVENT_NAME':'push'}),patch('gx06_stack_gate.git',return_value=x['identity']['source_sha']),patch('gx06_stack_gate.validate_hosted_context',return_value=(x['identity']['run_id'],x['identity']['run_attempt'])):
            self.assertEqual(g.evidence(**x,main_root=a.ROOT)['security_test_count'],7)
if __name__=='__main__':unittest.main(verbosity=2)
