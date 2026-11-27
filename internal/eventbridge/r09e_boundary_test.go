package eventbridge

import "testing"

func TestR09EBridgeNumericAndDigestBoundaries(t *testing.T) {
	for _, tc := range []struct {
		value any
		want  int64
		ok    bool
	}{
		{int(1), 1, true}, {int64(4), 4, true}, {float64(9), 9, true}, {float64(-2), -2, true},
		{float64(1.5), 0, false}, {"1", 0, false}, {nil, 0, false},
	} {
		got, ok := numberInt64(tc.value)
		if ok != tc.ok || (ok && got != tc.want) {
			t.Fatalf("numberInt64(%#v)=(%d,%t)", tc.value, got, ok)
		}
	}
	if !isHex("0011aAbBcCdDeEfF") || isHex("G") || isHex("0x12") || isHex("f-") {
		t.Fatal("hex alphabet loosened")
	}
	if !CompareDigest([]byte{0, 1, 2}, []byte{0, 1, 2}) || CompareDigest([]byte{0, 1, 2}, []byte{0, 1, 3}) {
		t.Fatal("digest comparison drift")
	}
}
