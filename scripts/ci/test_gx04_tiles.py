#!/usr/bin/env python3
"""GX04 P01 source-level, pure-Java, and hostile manifest regressions. No SDK/ADB/Gradle."""
from __future__ import annotations
import re
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]
JAVA=ROOT/'android-app/app/src/main/java/io/github/mikeceead/xbscompanion'
MANIFEST=ROOT/'android-app/app/src/main/AndroidManifest.xml'
ANDROID='{http://schemas.android.com/apk/res/android}'
CLASSES=('Pause','SafeMode','Saver','Profiles','RestoreDefaults')

class Gx04TileContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root=ET.parse(MANIFEST).getroot()
        cls.services=cls.root.findall('./application/service')
        cls.java=(JAVA/'XbsQuickTileService.java').read_text()
        cls.ctrl=(JAVA/'XbsTileController.java').read_text()
        cls.decision=(JAVA/'XbsTileDecision.java').read_text()

    def tile(self,name):
        entries=[s for s in self.services if s.get(ANDROID+'name')=='.Xbs'+name+'TileService']
        self.assertEqual(len(entries),1,'GX04 tile declaration count')
        return entries[0]

    def test_01_five_system_binder_tile_components(self):
        for name in CLASSES:
            with self.subTest(name=name):
                entry=self.tile(name)
                self.assertEqual(entry.get(ANDROID+'permission'),'android.permission.BIND_QUICK_SETTINGS_TILE')
                self.assertEqual(entry.get(ANDROID+'exported'),'true')
                actions=[v.get(ANDROID+'name') for v in entry.findall('intent-filter/action')]
                self.assertEqual(actions,['android.service.quicksettings.action.QS_TILE'])

    def test_02_active_tile_metadata(self):
        for name in CLASSES:
            tags=self.tile(name).findall('meta-data')
            self.assertEqual([(x.get(ANDROID+'name'),x.get(ANDROID+'value')) for x in tags],
                [('android.service.quicksettings.ACTIVE_TILE','true')])

    def test_03_unique_mono_permission_and_icon(self):
        for name in CLASSES:
            entry=self.tile(name)
            self.assertTrue(entry.get(ANDROID+'label','').startswith('XBS '))
            self.assertEqual(entry.get(ANDROID+'icon'),'@android:drawable/ic_menu_manage')

    def test_04_private_explicit_state_receiver(self):
        entries=[r for r in self.root.findall('./application/receiver') if r.get(ANDROID+'name')=='.XbsTileRefreshReceiver']
        self.assertEqual(len(entries),1)
        self.assertEqual(entries[0].get(ANDROID+'exported'),'false')
        self.assertEqual([v.get(ANDROID+'name') for v in entries[0].findall('intent-filter/action')],
            ['io.github.mikeceead.xbscompanion.STATE_UPDATED',
             'io.github.mikeceead.xbscompanion.RUN_UPDATED'])

    def test_05_no_privileged_executor_on_main_thread(self):
        self.assertIn('Executors.newSingleThreadExecutor()',self.java)
        self.assertIn('dispatch(()->',self.java)
        self.assertIn('isLocked()',self.java)
        self.assertNotIn('Runtime.getRuntime().exec(',self.java)

    def test_06_compat_navigation_pending_intent(self):
        self.assertRegex(self.java,r'Build\.VERSION\.SDK_INT\s*>=\s*34')
        self.assertIn('PendingIntent.FLAG_IMMUTABLE',self.java)
        self.assertIn('startActivityAndCollapse(pending)',self.java)

    def test_07_only_safe_actions_are_allowlisted(self):
        for name in ('pause','resume','safe_on','safe_off'):
            self.assertIn('"'+name+'"',self.decision)
        self.assertIn('SuiteCatalog.find("xbs",decision.action)',self.ctrl)
        self.assertIn('allowlisted.needsArgument',self.ctrl)
        self.assertIn('ExecutionSource.TILE',self.ctrl)

    def test_08_no_optimistic_state_claim(self):
        for evidence in ('bridge.authenticated()','bridge.sequence>0L','snap.available',
                         'bridge.capturedAt>started','snap.capturedAt>started',
                         'bridge.sequence>p.getLong("bridge_sequence",0L)'):
            self.assertIn(evidence,self.ctrl)
        self.assertIn('Phase.PENDING',self.decision)

    def test_09_pending_durable_commit(self):
        self.assertIn('.putString("action",decision.action)',self.ctrl)
        self.assertIn('.putLong("started",now)',self.ctrl)
        self.assertIn('.commit()',self.ctrl)
        self.assertIn('RunStore.active(context)',self.ctrl)

    def test_10_restore_requires_explicit_app_confirmation(self):
        self.assertIn('kind==XbsTileDecision.Kind.RESTORE',self.java)
        self.assertIn('openConfiguration()',self.java)
        self.assertIn('if(kind==XbsTileDecision.Kind.RESTORE)return false;',self.ctrl)
        activity=(JAVA/'XbsTileSettingsActivity.java').read_text()
        self.assertIn('Unlock LOCAL tile shortcut reset',activity)
        self.assertIn('Confirm local tile reset',activity)
        self.assertNotIn('runtime restore',self.ctrl)

    def test_11_manifest_attack_mutation_fails(self):
        source=MANIFEST.read_text()
        changed=source.replace('android.permission.BIND_QUICK_SETTINGS_TILE','android.permission.INTERNET',1)
        root=ET.fromstring(changed)
        elements=[s for s in root.findall('./application/service') if s.get(ANDROID+'name')=='.XbsPauseTileService']
        self.assertEqual(len(elements),1)
        self.assertNotEqual(elements[0].get(ANDROID+'permission'),'android.permission.BIND_QUICK_SETTINGS_TILE')

    def test_12_no_main_thread_privileged_method(self):
        chunk=self.java.split('@Override public void onClick()',1)[1].split('private void openConfiguration()',1)[0]
        self.assertIn('dispatch(()->',chunk)
        self.assertNotIn('RootCommandRunner.run(',chunk)

    def test_13_failure_is_not_active(self):
        self.assertIn('Phase.FAILED',self.decision)
        self.assertIn('Phase.RECOVERY_REQUIRED',self.decision)
        self.assertIn('Tile.STATE_UNAVAILABLE',self.java)

    def test_14_execute_pure_java_policy(self):
        compiler=shutil.which('javac')
        runtime=shutil.which('java')
        if not compiler or not runtime:self.skipTest('JDK unavailable; hosted JVM must execute this test')
        with tempfile.TemporaryDirectory(prefix='gx04-java-policy-') as temp:
            source=(JAVA/'XbsTileDecision.java')
            harness=ROOT/'scripts/ci/gx04_decision_harness.java.txt'
            result=subprocess.run([compiler,'-encoding','UTF-8','-d',temp,str(source),
                str(JAVA/'XbsTileCompletionPolicy.java'),str(harness_copy(temp,harness))],
                capture_output=True,text=True,timeout=40)
            self.assertEqual(result.returncode,0,result.stderr[-4000:])
            run=subprocess.run([runtime,'-cp',temp,'io.github.mikeceead.xbscompanion.Gx04DecisionHarness'],
                capture_output=True,text=True,timeout=40)
            self.assertEqual(run.returncode,0,run.stderr[-4000:])
            self.assertIn('GX04_NATIVE_PURE_JAVA_POLICY_PASS checks=',run.stdout)

    def test_15_immutable_production_quick_tile_subclasses(self):
        kinds={'Pause':'PAUSE','SafeMode':'SAFE','Saver':'SAVER','Profiles':'PROFILES','RestoreDefaults':'RESTORE'}
        for name,kind in kinds.items():
            content=(JAVA/('Xbs'+name+'TileService.java')).read_text()
            self.assertIn('extends XbsQuickTileService',content)
            self.assertIn('super(XbsTileDecision.Kind.'+kind+');',content)


    def test_16_event_selection_requires_authenticated_snapshot(self):
        config=(JAVA/'XbsTileConfiguration.java').read_text()
        self.assertIn('XbsTileController.trusted(snap,bridge,now)',config)
        self.assertIn('candidates(snap).contains(event)',config)
        self.assertIn('SuiteAction.validateArgument(event,true)',config)
        self.assertIn('event_run',self.decision)
        self.assertIn('event_stop',self.decision)

    def test_17_run_outcome_is_required_before_success(self):
        self.assertIn('RunStore.find(c,runId)',self.ctrl)
        self.assertIn('XbsTileCompletionPolicy.Outcome.RECOVERY_REQUIRED',self.ctrl)
        self.assertIn('confirmedTarget(snap,kind,event,target)',self.ctrl)
        self.assertIn('activePending(context)',self.ctrl)
        self.assertIn('"run_id",result.runId',self.ctrl)

    def test_18_all_five_tiles_are_user_visible_and_protected(self):
        self.assertEqual(len(self.root.findall('./application/service[@'+ANDROID+'permission="android.permission.BIND_QUICK_SETTINGS_TILE"]')),5)
        self.assertEqual(len([a for a in self.root.findall('./application/activity') if a.get(ANDROID+'name')=='.XbsTileSettingsActivity']),1)
        self.assertEqual([a.get(ANDROID+'exported') for a in self.root.findall('./application/activity') if a.get(ANDROID+'name')=='.XbsTileSettingsActivity'],['false'])

    def test_19_actual_hosted_api36_tile_tests_are_required(self):
        host=(ROOT/'scripts/ci/gx03_emulator_host.py').read_text()
        self.assertIn("gradle_test('io.github.mikeceead.xbscompanion.Gx04TilesInstrumentedTest')",host)
        self.assertIn("'allFiveTilesHaveSystemOnlyManifestPermission'",host)
        self.assertIn("'hostileEventNamesRejectedBeforeLaunch'",host)
        self.assertIn("report['gx04_tile_assertions']",host)

    def test_20_single_writer_no_device_in_test_or_host(self):
        self.assertIn('private static final Object LOCK',self.ctrl)
        self.assertIn('if(isLocked() || destroyed)',self.java)
        self.assertNotIn('Runtime.getRuntime().exec(',self.java)
        self.assertNotIn('su -c',self.java)
        self.assertNotIn('su -c',(ROOT/'scripts/ci/gx03_emulator_host.py').read_text())

    def test_21_command_terminal_result_mandatory(self):
        policy=(JAVA/'XbsTileCompletionPolicy.java').read_text()
        for status in ('"ok".equals(status)', '"failed".equals(status)',
                       '"cancelled".equals(status)', '"recovery_required".equals(status)'):
            self.assertIn(status,policy)
        self.assertIn('authenticated && newer && targetObserved',policy)
        self.assertIn('if (!"ok".equals(status)) return Outcome.WAIT;',policy)

    def test_22_controller_uses_exact_run_record_before_marking_confirmed(self):
        self.assertIn('RunStore.find(c,runId)',self.ctrl)
        self.assertIn('record==null?null:record.status',self.ctrl)
        self.assertIn('Outcome.CONFIRMED',self.ctrl)
        self.assertIn('Outcome.RECOVERY_REQUIRED',self.ctrl)
        self.assertIn('bridge.sequence>p.getLong("bridge_sequence",0L)',self.ctrl)

    def test_23_failure_and_timeout_do_not_replay_command(self):
        self.assertIn('putBoolean("recovery",true)',self.ctrl)
        self.assertIn('acknowledgeRecovery(',self.ctrl)
        self.assertIn('activePending(context)',self.ctrl)
        self.assertIn('if(kind==XbsTileDecision.Kind.RESTORE)return false;',self.ctrl)
        self.assertNotIn('Runtime.getRuntime().exec(',self.ctrl)

    def test_24_hosted_four_extra_tests_executed_as_required(self):
        host=(ROOT/'scripts/ci/gx03_emulator_host.py').read_text()
        instrumentation=(ROOT/'android-app/app/src/androidTest/java/io/github/mikeceead/xbscompanion/Gx04TilesInstrumentedTest.java').read_text()
        for method in ('verifiedCompletionRequiresTerminalHistoryAndNewerEvidence',
                       'unverifiedTilesCannotLaunchRootCommands',
                       'runtimeSettingsActivityRendersAllFiveTileRequests',
                       'durablePendingBlocksRepeatedTapAcrossReload'):
            self.assertIn("'"+method+"'",host)
            self.assertIn('void '+method+'()',instrumentation)
        # Require all nine original and six new Android tests to appear once,
        # and be required by the real hosted JUnit receipt.
        from r08_r10_acceptance import validate_complete_gx04_inventory
        self.assertEqual(validate_complete_gx04_inventory(instrumentation,host),15)
        self.assertIn('ActivityScenario.launch(XbsTileSettingsActivity.class)',instrumentation)
        self.assertIn('gx04_systemui_probe(serial)',host)
        self.assertIn('GX04_SYSTEMUI_CALLBACK_MISSING',host)

    def test_25_gx04_original_coverage_is_explicitly_unqualified(self):
        import json
        matrix=json.loads((ROOT/'docs/engineering/XBS_GX04_COVERAGE.json').read_text())
        self.assertEqual(len(matrix['milestone']),6)
        self.assertEqual([x['id'] for x in matrix['original_R08']],
            ['R08-'+str(i).zfill(2) for i in range(1,15)])
        self.assertEqual([x['id'] for x in matrix['original_emulator_cases']],
            ['EM-019','EM-020','EM-021'])
        self.assertTrue(all(x['accepted'] is False for x in
            matrix['original_R08']+matrix['original_emulator_cases']))

    def test_26_real_tile_navigation_and_lock_rejection_contract(self):
        activity=(JAVA/'XbsTileSettingsActivity.java').read_text()
        self.assertIn('requestAddTileService(',activity)
        self.assertIn('Unlock LOCAL tile shortcut reset',activity)
        self.assertIn('Confirm local tile reset',activity)
        self.assertIn('isLocked() || destroyed',self.java)
        self.assertIn('Executors.newSingleThreadExecutor()',self.java)
        self.assertIn('dispatchCommand(()->',activity)
        self.assertIn('XbsTileController.requestToggle(getApplicationContext(),kind,true)',activity)
        self.assertIn('XbsTileController.acknowledgeRecovery(getApplicationContext(),kind)',activity)

    def test_27_unverified_tile_remains_clickable_but_never_active(self):
        java=(JAVA/'XbsQuickTileService.java').read_text()
        self.assertIn('decision.phase==XbsTileDecision.Phase.PENDING?Tile.STATE_UNAVAILABLE',java)
        self.assertIn('Tile.STATE_INACTIVE);',java)
        self.assertIn('state.action==null ||',java)
        self.assertIn('main.post(this::openConfiguration)',java)
        self.assertIn('if(isLocked() || destroyed)',java)
        self.assertIn('state.phase==XbsTileDecision.Phase.ACTIVE',java)

    def test_28_click_callback_not_assumed_from_shell_success(self):
        host=(ROOT/'scripts/ci/gx03_emulator_host.py').read_text()
        self.assertIn("statusbar('expand-settings')",host)
        self.assertIn("wait_delta('clicked_PAUSE'",host)
        self.assertIn("'GX04_SYSTEMUI_CALLBACK_MISSING clicked_PAUSE counts='",host)
        self.assertIn("statusbar('remove-tile',True)",host)
        self.assertIn('shell_exit_alone_never_proves_callback',host)
        self.assertIn('root_execution_asserted',host)


class ActiveTileLifecycleProbeTests(unittest.TestCase):
    def exercise(self, *, omit=(), stale=False, duplicate=False, refuse=None):
        from types import SimpleNamespace
        from unittest.mock import patch
        import gx03_emulator_host as host
        counters={name+'_PAUSE':7 for name in ('added','listening','clicked','stopped','removed')} if stale else {}
        commands=[]
        def bump(name):
            if name not in omit:
                key=name+'_PAUSE';counters[key]=counters.get(key,0)+1
        def adb(serial,*args,**kw):
            self.assertEqual(serial,'emulator-5554')
            self.assertEqual(args[:3],('shell','cmd','statusbar'))
            command=args[3];commands.append(command)
            if command==refuse:return SimpleNamespace(returncode=1,stdout='',stderr='refused')
            if command=='add-tile':bump('added')
            # ACTIVE_TILE panel opening does not trigger a listening callback.
            if command=='click-tile':
                bump('listening');bump('clicked');bump('stopped')
                if duplicate:bump('clicked')
            if command=='remove-tile':bump('removed')
            return SimpleNamespace(returncode=0,stdout='true' if command=='check-support' else '',stderr='')
        self.commands=commands
        with patch.object(host,'adb',side_effect=adb),patch.object(host,'gx04_systemui_counts',side_effect=lambda _:dict(counters)),patch('time.sleep'):
            return host.gx04_systemui_probe('emulator-5554')

    def test_active_tile_click_triggers_listening_not_panel(self):
        proof=self.exercise(stale=True)
        self.assertEqual(self.commands,['check-support','add-tile','expand-settings','click-tile','collapse','remove-tile','collapse'])
        self.assertTrue(all(proof[k] for k in ('add_callback','click_callback','remove_callback','start_listening_callback','stop_listening_callback')))
        self.assertEqual(proof['fresh_click_callback_deltas'],{'listening_PAUSE':1,'clicked_PAUSE':1,'stopped_PAUSE':1})
        self.assertEqual(proof['click_dispatch_count'],1)
        self.assertFalse(proof['root_execution_asserted'])

    def test_each_callback_must_be_fresh_even_with_stale_counters(self):
        from gx03_evidence import Refused
        for marker,message in [('added','CALLBACK_MISSING added'),('listening','START_LISTENING_CALLBACK_MISSING'),('clicked','CALLBACK_MISSING clicked'),('stopped','STOP_LISTENING_CALLBACK_MISSING'),('removed','REMOVE_CALLBACK_MISSING')]:
            with self.subTest(marker=marker),self.assertRaisesRegex(Refused,message):
                self.exercise(omit=(marker,),stale=True)
            self.assertEqual(self.commands.count('remove-tile'),1)
            self.assertLessEqual(self.commands.count('click-tile'),1)

    def test_duplicate_click_is_not_accepted_or_retried(self):
        from gx03_evidence import Refused
        with self.assertRaisesRegex(Refused,'DUPLICATE_CALLBACK'):
            self.exercise(duplicate=True)
        self.assertEqual(self.commands.count('click-tile'),1)
        self.assertIn('remove-tile',self.commands)

    def test_command_refusal_cleans_up_without_claiming_callbacks(self):
        from gx03_evidence import Refused
        with self.assertRaisesRegex(Refused,'COMMAND_REFUSED click-tile'):
            self.exercise(refuse='click-tile')
        self.assertEqual(self.commands.count('click-tile'),1)
        self.assertIn('remove-tile',self.commands)

def harness_copy(temp,path):
    target=Path(temp)/'Gx04DecisionHarness.java'
    target.write_bytes(path.read_bytes())
    return target

if __name__=='__main__':unittest.main(verbosity=2)
