"""Read-only R05A source contracts. Does not launch Android, root, or Gradle."""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
ANDROID=ROOT/'android-app/app/src/main/java/io/github/mikeceead/xbscompanion'

class R05aSourceContracts(unittest.TestCase):
    def text(self, relative):
        return (ROOT/relative).read_text(encoding='utf-8')
    def test_controller_request_receipts_are_id_bound(self):
        control=self.text('internal/control/control.go')
        self.assertIn('obj["request_id"]=requestIDs[0]',control)
        self.assertIn('waitForObservedState(action, event, before, opts.timeout)',control)
        self.assertIn('waitForObservedState(action, "", before, opts.timeout)',control)
    def test_observation_is_not_just_ack(self):
        go=self.text('internal/control/r05a_observation.go')
        self.assertIn('after.LastReloadTime!=before.LastReloadTime',go)
        self.assertIn('if !after.Available',go)
    def test_control_dispatch_waits(self):
        runner=(ANDROID/'RootCommandRunner.java').read_text()
        self.assertIn('R05aCommandPolicy.forceWait(command)',runner)
        self.assertIn('receipt.applied()',runner)
        self.assertIn('receipt.taskerVariables(variables)',runner)
    def test_tasker_requires_application_not_queue(self):
        r=(ANDROID/'R05aDaemonReceipt.java').read_text()
        for value in ['request_id','requested_action','completion','observed','active_events','last_reload_time','mode_mismatch']:
            self.assertIn(value,r)
        self.assertIn('"queued".equals(completion)',r)
        self.assertIn('State.RECOVERY_REQUIRED',r)
    def test_tasker_identity_remains_verified(self):
        r=(ANDROID/'TaskerPluginReceiver.java').read_text()
        self.assertIn('TaskerAuthorization.verify(context,config,false)',r)
        self.assertIn('TaskerConfigCodec.senderMatches(context,sender,senderUid,host)',r)
        self.assertLess(r.index('TaskerAuthorization.verify('),r.index('R05aActionDedup.claim('))
    def test_recovery_is_not_mislabeled_as_success(self):
        run=(ANDROID/'RunStore.java').read_text()
        retry=(ANDROID/'RunRetryPolicy.java').read_text()
        self.assertIn('receipt.historyStatus()',run)
        self.assertIn('"recovery_required".equals(record.status)',retry)
    def test_service_does_not_leak_active_count_on_race(self):
        service=(ANDROID/'RootCommandService.java').read_text()
        self.assertIn('if (!ownedCommand.state.compareAndSet(0, 1)) return;',service)
        self.assertIn('A rejected claim MUST NOT decrement activeRuns again.',service)
        self.assertLess(service.index('if (!ownedCommand.state.compareAndSet'),service.index('activeRuns.decrementAndGet()'))
    def test_config_reload_does_not_auto_restore_unknown(self):
        tx=(ANDROID/'XbsConfigTransaction.java').read_text()
        self.assertIn('RELOAD_STARTED=true;',tx)
        self.assertIn('write_status recovery_required',tx)
        self.assertIn('xbsctl("--json reload --wait --timeout 12")',tx)
    def test_hosted_android_jvm_job_is_present(self):
        ci=self.text('.github/workflows/devtool-xbs-ci.yml')
        self.assertIn('android-companion-jvm:',ci)
        self.assertIn(':app:testDebugUnitTest',ci)
        self.assertIn('gradle/actions/setup-gradle@v4',ci)

if __name__=='__main__':unittest.main()
