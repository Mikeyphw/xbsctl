#!/usr/bin/env python3
"""AndroidX API36 migration gate for real legacy androidTest classes, never skip tests.

Runs as part of r05c_full_ci_precommit.py --run (Termux and GitHub Actions).
"""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
ANDROID_TEST=ROOT/'android-app/app/src/androidTest/java/io/github/mikeceead/xbscompanion'
A=ANDROID_TEST/'AccessibilityAdaptiveInstrumentedTest.java'
D=ANDROID_TEST/'DebugHealthReadOnlyInstrumentedTest.java'

def check(a,d):
    for label,source in [('accessibility',a),('debug_health',d)]:
        if 'android.test.' in source or 'extends InstrumentationTestCase' in source or 'extends AndroidTestCase' in source:
            raise AssertionError(label+': obsolete android.test dependency present')
        for marker in ('@RunWith(AndroidJUnit4.class)','import androidx.test.ext.junit.runners.AndroidJUnit4;',
                       'import org.junit.Test;', 'import org.junit.runner.RunWith;', 'import static org.junit.Assert.*;'):
            if marker not in source:raise AssertionError(label+': missing AndroidX/JUnit4 '+marker)
    a_tests=['testProductionPolicyCoversDirectSpinnerAndCheckbox',
             'testRepresentativeRealScreensExposeMeaningfulPaneAndHeading',
             'testCancelActionIsDurablyOneUse']
    for name in a_tests:
        if '@Test public void '+name+'(' not in a:
            raise AssertionError('accessibility: missing runnable JUnit4 test '+name)
    if a.count('@Test public void ')!=3:
        raise AssertionError('accessibility: expected all three JUnit4 tests')
    if a.count('InstrumentationRegistry.getInstrumentation()')!=3 or 'getInstrumentation()' in a.replace('InstrumentationRegistry.getInstrumentation()',''):
        raise AssertionError('accessibility: modern instrumentation access must replace legacy call')
    if a.count('i.runOnMainSync(()->')!=1 or 'runTestOnUiThread(' in a:
        raise AssertionError('accessibility: main-thread assertion runner missing')
    for guard in ('catch(Throwable t){error[0]=t;}',
                  'assertTouchTargets(root,', 'assertTrue(ViewCompat.isAccessibilityHeading(title))',
                  'assertEquals(0,result.failures)', 'assertActivity(CliCommandActivity.class,Spinner.class)',
                  'assertActivity(AppPickerActivity.class,CheckBox.class)',
                  'assertTrue(RuntimeNotificationStore.consumeCancelAction(',
                  'assertFalse(RuntimeNotificationStore.consumeCancelAction('):
        if guard not in a:raise AssertionError('accessibility: required behavior removed '+guard)
    method='testEvaluationDoesNotMutateHistoryLegacyPayloadTransactionCacheOrDatabase'
    if '@Test public void '+method+'(' not in d or d.count('@Test public void ')!=1:
        raise AssertionError('debug_health: required JUnit4 test not runnable')
    if 'ApplicationProvider.getApplicationContext()' not in d or 'getContext()' in d:
        raise AssertionError('debug_health: AndroidX app context required')
    for guard in ('DebugHealthEvaluator.evaluate(app);',
                  'assertEquals(legacyBefore, legacy.getAll());',
                  'assertEquals(transaction, cache.getString(',
                  'assertEquals(transactionAt, cache.getLong(',
                  'assertDatabaseSnapshotEquals(databaseBefore, databaseSnapshot(app));',
                  'Arrays.equals(expected.get(key), actual.get(key))',
                  'new String[] {"", "-wal", "-shm"}'):
        if guard not in d:raise AssertionError('debug_health: read-only evidence guard removed '+guard)
    return True

class AndroidXLegacyMigration(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.a=A.read_text(encoding='utf-8')
        cls.d=D.read_text(encoding='utf-8')
    def test_real_android_classes_preserve_runnable_checks(self):
        self.assertTrue(check(self.a,self.d))
    def test_legacy_android_test_dependency_is_rejected(self):
        with self.assertRaisesRegex(AssertionError,'obsolete android.test'):
            check(self.a.replace('import android.app.Activity;','import android.test.AndroidTestCase;\nimport android.app.Activity;'), self.d)
    def test_lost_junit4_test_annotation_rejected(self):
        with self.assertRaisesRegex(AssertionError,'runnable JUnit4'):
            check(self.a.replace('@Test public void testCancelActionIsDurablyOneUse','public void testCancelActionIsDurablyOneUse'),self.d)
    def test_lost_accessibility_assertion_rejected(self):
        with self.assertRaisesRegex(AssertionError,'required behavior removed'):
            check(self.a.replace('assertEquals(0,result.failures)','/* removed */'),self.d)
    def test_lost_cancel_one_time_guard_rejected(self):
        with self.assertRaisesRegex(AssertionError,'required behavior removed'):
            check(self.a.replace('assertFalse(RuntimeNotificationStore.consumeCancelAction(','assertTrue(RuntimeNotificationStore.consumeCancelAction('),self.d)
    def test_missing_main_thread_execution_rejected(self):
        with self.assertRaisesRegex(AssertionError,'main-thread'):
            check(self.a.replace('i.runOnMainSync(()->','i.runOnFakeThread(()->'),self.d)
    def test_bad_instrumentation_access_rejected(self):
        with self.assertRaisesRegex(AssertionError,'modern instrumentation'):
            check(self.a.replace('InstrumentationRegistry.getInstrumentation()','getInstrumentation()'),self.d)
    def test_debug_health_app_context_required(self):
        with self.assertRaisesRegex(AssertionError,'AndroidX app context'):
            check(self.a,self.d.replace('ApplicationProvider.getApplicationContext()','getContext()'))
    def test_database_snapshot_preservation_required(self):
        with self.assertRaisesRegex(AssertionError,'read-only evidence'):
            check(self.a,self.d.replace('assertDatabaseSnapshotEquals(databaseBefore, databaseSnapshot(app));','// removed'))
    def test_wal_shm_verification_required(self):
        with self.assertRaisesRegex(AssertionError,'read-only evidence'):
            check(self.a,self.d.replace('new String[] {"", "-wal", "-shm"}','new String[] {""}'))

if __name__=='__main__':unittest.main(verbosity=2)
