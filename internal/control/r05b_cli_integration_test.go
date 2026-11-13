package control

import (
    "bytes"
    "strings"
    "testing"
)

func TestR05BExpectedGenerationRequiresAwaitedReload(t *testing.T) {
    x:=strings.Repeat("a",64)
    for _,args:=range [][]string{
        {"--json","reload","--expect-config-sha256",x},
        {"--json","pause","--wait","--expect-config-sha256",x},
        {"--json","reload","--wait","--expect-config-sha256","NOT-A-SHA"},
    } {
        var out,err bytes.Buffer
        if code:=Main(args,&out,&err);code!=ExitInvalidArgs {t.Errorf("allowed unsafe flag combination %v, code=%d stdout=%s",args,code,out.String())}
    }
    snapshot:=snapshotFromRaw(map[string]any{"mode":"normal","config_sha256":x})
    if !r05bMatchingGeneration(x,snapshot.ConfigSHA256) {t.Fatal("snapshot dropped generation")}
}
