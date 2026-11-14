package eventbridge

import (
    "encoding/json"
    "testing"
)

func FuzzR09AHexDigest(f *testing.F) {
    for _, seed := range []string{"", "0", "aAbB019F", "xyz", "dead/beef", "\x00", "⚡"} { f.Add(seed) }
    f.Fuzz(func(t *testing.T, value string) {
        if !isHex(value) { return }
        for _, c:=range value {
            if !(c>='0'&&c<='9'||c>='a'&&c<='f'||c>='A'&&c<='F') { t.Fatalf("nonhex accepted: %q",value) }
        }
    })
}

func FuzzR09ACanonicalJSON(f *testing.F) {
    for _, seed:=range [][]byte{[]byte(`{"a":1}`),[]byte(`null`),[]byte(`[true,false]`),[]byte(`"text"`),[]byte(`not-json`),[]byte(`{"x":"\\u0000"}`)} { f.Add(seed) }
    f.Fuzz(func(t *testing.T, raw []byte) {
        // Bound input and refuse invalid source; no device, network or fs use.
        if len(raw)>32768 { return }
        var obj any
        if json.Unmarshal(raw,&obj)!=nil { return }
        encoded:=CanonicalJSON(obj)
        if !json.Valid(encoded) { t.Fatalf("invalid canonical JSON") }
        if string(CanonicalJSON(obj))!=string(encoded) { t.Fatalf("nondeterministic canonical JSON") }
    })
}

func TestR09ADigestMalformedInputs(t *testing.T) {
    for _, s:=range []string{"xyz", "0/0", "0\x00", "é"} {
        if isHex(s) {t.Fatalf("malformed digest accepted: %q",s)}
    }
}
