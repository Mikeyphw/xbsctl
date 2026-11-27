"""Actual Java lexical policy plus fail-closed source/evidence regressions."""
import unittest, json, tempfile, subprocess, shutil
from pathlib import Path
from gx10_editor import validate_editor_source,validate_handoff,APP,PACKAGE,EVIDENCE
from gx03_evidence import Refused
ROOT=Path(__file__).resolve().parents[2]
class EditorTests(unittest.TestCase):
    def test_source_and_historical_evidence(self):
        validate_editor_source(ROOT);validate_handoff(ROOT)
    def test_historical_acceptance_refused(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);p=root/EVIDENCE;p.parent.mkdir(parents=True);data=json.loads((ROOT/EVIDENCE).read_bytes());data['original_cases_accepted']=['EM-015'];p.write_text(json.dumps(data));shutil.copyfile(ROOT/'docs/engineering/XBS_TEST_MATRIX.json',p.parent/'XBS_TEST_MATRIX.json')
            with self.assertRaisesRegex(Refused,'UI02_ORIGINAL_MAPPING_DRIFT'):validate_handoff(root)
    def test_release_fixture_refused(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            for relative in (APP,Path('scripts/ci'),Path('.github/workflows')):shutil.copytree(ROOT/relative,root/relative)
            p=root/APP/'release/java'/PACKAGE/'EditorBackendProvider.java';p.write_text(p.read_text()+'\n// installForInstrumentation\n')
            with self.assertRaisesRegex(Refused,'UI02_RELEASE_FIXTURE_LEAK'):validate_editor_source(root)
    def test_actual_java_essentials_policy(self):
        base=ROOT/APP/'main/java'/PACKAGE
        harness='''package io.github.mikeceead.xbscompanion;
import java.util.Set;
public final class EssentialsHarness {
  public static void main(String[] args) {
    String root="/storage/emulated/0/.config/xbs/lists", good=root+"/essentials.allowlist", alias=root+"/event.allowlist";
    if(!EssentialsDraftPolicy.pathError(root,good,Set.of(alias)).isEmpty())throw new AssertionError("valid");
    for(String path:new String[]{"/etc/passwd",root+"/../x.allowlist",root+"/nested/x.allowlist",alias,good+" ",root+"/x.proc",root+"//x.allowlist"})
      if(EssentialsDraftPolicy.pathError(root,path,Set.of(alias)).isEmpty())throw new AssertionError(path);
    if(!"android\\ncom.example.app\\n".equals(EssentialsDraftPolicy.normalize("# note\\r\\nandroid\\r\\ncom.example.app\\nandroid\\n")))throw new AssertionError("normalization");
    for(String bad:new String[]{"", "# only", "com.example;id", "com..bad", "com.example app", "com.example/other", "x"}) {
      try { EssentialsDraftPolicy.normalize(bad);throw new AssertionError(bad); } catch(IllegalArgumentException expected){}
    }
  }
}'''
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'EssentialsHarness.java';p.write_text(harness)
            subprocess.run(['javac','-d',d,str(base/'ManagedPathPolicy.java'),str(base/'EssentialsDraftPolicy.java'),str(p)],check=True,timeout=60)
            subprocess.run(['java','-cp',d,'io.github.mikeceead.xbscompanion.EssentialsHarness'],check=True,timeout=30)
if __name__=='__main__':unittest.main(verbosity=2)
