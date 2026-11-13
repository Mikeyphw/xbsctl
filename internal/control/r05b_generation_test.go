package control

import (
    "strings"
    "testing"
)

func TestR05BExactGenerationMatchingAndRefusals(t *testing.T) {
    x := strings.Repeat("a",64)
    for _, bad := range []string{"",strings.Repeat("a",63),strings.Repeat("A",64),strings.Repeat("z",64),strings.Repeat("a",65)} {
        if r05bValidGeneration(bad) || r05bMatchingGeneration(x,bad) {t.Errorf("accepted malformed generation %q",bad)}
    }
    if !r05bMatchingGeneration(x,x) {t.Fatal("same validated generation rejected")}
    if r05bMatchingGeneration(x,strings.Repeat("b",64)) {t.Fatal("different generation accepted")}
}

