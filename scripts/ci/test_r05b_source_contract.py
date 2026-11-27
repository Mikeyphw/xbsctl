import pathlib,re,unittest,json
ROOT=pathlib.Path(__file__).resolve().parents[2]
def src(p):return (ROOT/p).read_text(encoding='utf-8')
C='internal/control/control.go'
D='internal/daemon/daemon.go'
A='android-app/app/src/main/java/io/github/mikeceead/xbscompanion/XbsConfigTransaction.java'
G='internal/config/r05b_source_digest.go'
class R05BContract(unittest.TestCase):
 def test_single_read_generation(self):
  t=src(G)
  self.assertIn('os.ReadFile(path)',t)
  self.assertIn('json.Unmarshal(data, &raw)',t)
  self.assertIn('sha256.Sum256(data)',t)
  self.assertIn('info.Mode().IsRegular()',t)
 def test_validated_generation_published(self):
  t=src(D)
  self.assertIn('config.LoadWithSourceDigest()',t)
  self.assertIn('ConfigSHA256: loadedSHA',t)
  self.assertIn('s.ConfigSHA256 = freshSHA',t)
  self.assertIn('"config_sha256": s.ConfigSHA256',t)
 def test_controller_exact_bound_flag(self):
  t=src(C)
  self.assertIn('case "--expect-config-sha256":',t)
  self.assertIn('r05bMatchingGeneration(opts.expectConfigSHA, observed.ConfigSHA256)',t)
  self.assertIn('opts.expectConfigSHA',t)
  self.assertIn('req.ID',t)
 def test_new_generation_loaded_not_only_timestamp(self):
  t=src(C)
  self.assertIn('!waitForObservedState(action, "", before, opts.timeout)',t)
  self.assertIn('observed.ConfigSHA256',t)
 def test_three_paths_bind_daemon_receipt(self):
  t=src(A)
  self.assertEqual(t.count('confirmedReload('),4)
  self.assertNotIn('xbsctl("--json reload")',t)
  self.assertIn('plan.entries.get(0).newDigest',t)
  self.assertIn('originalConfigDigest(record)',t)
 def test_fail_closed_transient_states(self):
  t=src(A)
  self.assertIn('case "recovery_required":',t)
  self.assertIn('"recovery_required".equals(status)',t)
  self.assertIn('write_status recovery_required',t)
  self.assertIn('reload_pending|recovery_required',t)
 def test_prior_transaction_lock_guard(self):
  t=src(A)
  self.assertIn('acquire_lock; guard_previous',t)
  self.assertIn('Unresolved prior transaction',t)
  self.assertIn('if [ -L \\"$POINTER\\" ]',t)
 def test_no_root_test_execution(self):
  t=src('docs/engineering/XBS_R05B_IMPLEMENTATION_NOTE.md')
  self.assertIn('API36 instrumentation',t)
  self.assertIn('NOT claimed qualified',t)
 def test_legacy_config_and_r05a_safe(self):
  t=src('internal/config/config.go')
  self.assertIn('cfg, _, err := LoadWithSourceDigest()',t)
  self.assertIn('func Save(',t)
if __name__=='__main__':unittest.main()
