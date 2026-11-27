"""Execute the production shell generator in an isolated unprivileged filesystem.
Android transport and daemon validation are explicit stubs, not device evidence.
"""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
PKG='io.github.mikeceead.xbscompanion'
JAVA=ROOT/'android-app/app/src/main/java'/PKG.replace('.','/')
STUBS={
'org/json/JSONException.java':'package org.json; public class JSONException extends Exception {}',
'org/json/JSONObject.java':'package org.json; public class JSONObject { public String toString(int n) throws JSONException { return "{\\"new\\":true}\\n"; } }',
'XbsConfigApi.java':'''final class XbsConfigApi {
 static final String ROOT=System.getProperty("xbs.root")+"/.config/xbs", CONFIG=ROOT+"/XtremeBS.json", LIST_DIR=ROOT+"/lists", BACKUP_DIR=ROOT+"/backups";
 static String q(String s){return RootFileSnapshot.q(s);}
}''',
'XbsRuntimeResolver.java':'''final class XbsRuntimeResolver {static String xbs(String args){return System.getProperty("xbs.validate","true");}static String xbsCtl(String args){return "true";}}''',
'XbsRootApi.java':'''final class XbsRootApi {static final class CommandOutput {final int exitCode;final String stdout,error;CommandOutput(int c,String s,String e){exitCode=c;stdout=s;error=e;}} static CommandOutput runRoot(String s){throw new AssertionError("root unavailable");} static CommandOutput runRootLarge(String s,int n){throw new AssertionError("root unavailable");}}''',
'Harness.java':'''import java.util.*;import java.nio.charset.StandardCharsets;import org.json.JSONObject;
public class Harness { public static void main(String[] a)throws Exception {
 switch(a[0]){
 case "snapshot":System.out.print(RootFileSnapshot.readShell(a[1],XbsConfigApi.ROOT,XbsConfigApi.LIST_DIR,RootFileSnapshot.LIST_LIMIT));break;
 case "decode":RootFileSnapshot s=RootFileSnapshot.decode(new String(System.in.readAllBytes(),StandardCharsets.UTF_8).stripTrailing(),RootFileSnapshot.LIST_LIMIT);System.out.print(s.ok? s.generation:"ERROR");break;
 case "execute":String p=XbsConfigApi.LIST_DIR+"/event.allowlist";System.out.print(XbsConfigTransaction.buildExecuteShellForTest(XbsConfigTransaction.create(new JSONObject(),Map.of(p,"new\\n"),Set.of(),false,a[1],Map.of(p,a[2]))));break;
 case "latest":System.out.print(XbsConfigTransaction.buildReadLatestShellForTest());break;
 case "rollback":XbsConfigTransaction.Record r=XbsConfigTransaction.parseRecordForTest(new String(System.in.readAllBytes(),StandardCharsets.UTF_8));if(!r.canRollback())throw new AssertionError(r.error+" "+r.status);System.out.print(XbsConfigTransaction.buildRollbackShellForTest(r));break;
 default:throw new AssertionError(a[0]);
 }}}'''}

def digest(data):return hashlib.sha256(data).hexdigest()

class TransactionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build=tempfile.TemporaryDirectory();cls.addClassCleanup(cls.build.cleanup);base=Path(cls.build.name);files=[]
        for name,source in STUBS.items():
            p=base/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source if name.startswith('org/') else 'package '+PKG+';\n'+source);files.append(str(p))
        subprocess.run(['javac','-d',str(base),*files,*[str(JAVA/n) for n in ('RootFileSnapshot.java','ManagedPathPolicy.java','XbsConfigTransaction.java')]],check=True,timeout=60)
    def setUp(self):
        d=tempfile.TemporaryDirectory();self.addCleanup(d.cleanup);self.root=Path(d.name);self.config=self.root/'.config/xbs/XtremeBS.json';self.list=self.config.parent/'lists/event.allowlist';self.list.parent.mkdir(parents=True);self.config.write_bytes(b'{ "old": true }\n');self.list.write_bytes(b'old\n');self.before=self.config.read_bytes();self.old=self.list.read_bytes();self.tx=self.config.parent/'backups/transactions';self.bin=self.root/'bin';self.bin.mkdir();self.sync=self.bin/'sync';self.sync.write_text('#!/bin/sh\nexit 0\n');self.sync.chmod(0o755);self.env={**os.environ,'PATH':str(self.bin)+os.pathsep+os.environ['PATH']}
    def java(self,*args,input='',validate='true'):
        p=subprocess.run(['java','-cp',self.build.name,'-Dxbs.root='+str(self.root),'-Dxbs.validate='+validate,PKG+'.Harness',*args],input=input,text=True,capture_output=True,timeout=20)
        self.assertEqual(p.returncode,0,p.stderr);return p.stdout
    def shell(self,script):return subprocess.run(['/bin/sh','-c',script],env=self.env,text=True,capture_output=True,timeout=20)
    def execute(self,c=None,l=None,validate='true'):
        return self.shell(self.java('execute',digest(self.before) if c is None else c,digest(self.old) if l is None else l,validate=validate))
    def latest(self):
        p=self.shell(self.java('latest'));self.assertEqual(p.returncode,0,p.stderr);return p.stdout
    def rollback(self):return self.shell(self.java('rollback',input=self.latest()))
    def assertOriginal(self):self.assertEqual(self.config.read_bytes(),self.before);self.assertEqual(self.list.read_bytes(),self.old)
    def test_snapshot_exact_bytes_and_absence(self):
        for value in (b'  abc\r\n\n',b''):
            self.list.write_bytes(value);p=self.shell(self.java('snapshot',str(self.list)));self.assertEqual(p.returncode,0,p.stderr);self.assertEqual(self.java('decode',input=p.stdout),digest(value))
        self.list.unlink();p=self.shell(self.java('snapshot',str(self.list)));self.assertEqual(self.java('decode',input=p.stdout),'-');self.assertEqual(self.java('decode',input='XBS_SNAPSHOT_V1:5\nYQ==\nXBS_SNAPSHOT_END'),'ERROR')
    def test_stale_config_and_list_refused(self):
        for c,l in (('0'*64,None),(None,'0'*64),(None,'-')):
            with self.subTest(c=c,l=l):
                p=self.execute(c,l);self.assertEqual(p.returncode,77,p.stderr);self.assertOriginal();self.assertFalse((self.tx/'latest').exists());self.assertFalse((self.tx/'.lock').exists())
    def test_commit_and_rollback_preserve_old_temp_aliases(self):
        sentinel=self.root/'sentinel';sentinel.write_text('untouched');alias=Path(str(self.config)+'.xbsapp.tx');alias.symlink_to(sentinel);self.tx.mkdir(parents=True);(self.tx/'latest.tmp').symlink_to(sentinel)
        p=self.execute();self.assertEqual(p.returncode,0,p.stderr);self.assertEqual(self.list.read_bytes(),b'new\n');self.assertIn('status=committed',self.latest());p=self.rollback();self.assertEqual(p.returncode,0,p.stderr);self.assertOriginal();self.assertEqual(sentinel.read_text(),'untouched');self.assertTrue(alias.is_symlink())
    def test_validation_failure_rolls_back(self):
        p=self.execute(validate='false');self.assertNotEqual(p.returncode,0,p.stderr);self.assertOriginal();self.assertIn('status=rolled_back',self.latest())
    def test_symlink_refusal(self):
        self.list.unlink();target=self.root/'target';target.write_bytes(self.old);self.list.symlink_to(target);p=self.execute();self.assertEqual(p.returncode,73,p.stderr);self.assertEqual(target.read_bytes(),self.old);self.assertEqual(self.config.read_bytes(),self.before)
        self.assertEqual(self.shell(self.java('snapshot',str(self.list))).returncode,73)
    def test_tampered_backup_refused(self):
        p=self.execute();self.assertEqual(p.returncode,0,p.stderr);t=self.tx/(self.tx/'latest').read_text().strip();(t/'original/0').write_bytes(b'corrupt');p=self.rollback();self.assertEqual(p.returncode,78,p.stderr);self.assertEqual(self.list.read_bytes(),b'new\n');self.assertIn('status=committed',self.latest())
    def test_stale_after_staging_never_rolls_back_external_write(self):
        self.sync.write_text('#!/bin/sh\nfor s in '+str(self.tx)+'/*/status; do\nif [ -f "$s" ] && grep -q "^status=prepared$" "$s" && [ ! -e '+str(self.root/'fired')+' ]; then\nprintf external > '+str(self.list)+'\ntouch '+str(self.root/'fired')+'\nfi\ndone\n')
        p=self.execute();self.assertEqual(p.returncode,77,p.stderr);self.assertEqual(self.config.read_bytes(),self.before);self.assertEqual(self.list.read_bytes(),b'external');self.assertIn('status=rolled_back',self.latest())
    def test_active_lock_refuses_mutation(self):
        lock=self.tx/'.lock';lock.mkdir(parents=True);(lock/'id').write_text('active');(lock/'pid').write_text(str(os.getpid()));p=self.execute();self.assertEqual(p.returncode,75,p.stderr);self.assertOriginal();self.assertTrue(lock.exists())
    def test_ancestor_symlink_refuses_read_and_write(self):
        parent=self.list.parent;target=self.root/'real-lists';parent.rename(target);parent.symlink_to(target,target_is_directory=True);p=self.execute();self.assertEqual(p.returncode,73,p.stderr);self.assertEqual((target/self.list.name).read_bytes(),self.old);self.assertEqual(self.shell(self.java('snapshot',str(self.list))).returncode,73)
    def test_rollback_refuses_newer_managed_file(self):
        p=self.execute();self.assertEqual(p.returncode,0,p.stderr);self.list.write_bytes(b'newer');p=self.rollback();self.assertEqual(p.returncode,77,p.stderr);self.assertEqual(self.list.read_bytes(),b'newer');self.assertIn('status=committed',self.latest())
    def test_second_stale_writer_refused(self):
        p=self.execute();self.assertEqual(p.returncode,0,p.stderr);p=self.execute();self.assertEqual(p.returncode,77,p.stderr);self.assertEqual(self.list.read_bytes(),b'new\n');self.assertIn('status=committed',self.latest())

if __name__=='__main__':unittest.main(verbosity=2)
