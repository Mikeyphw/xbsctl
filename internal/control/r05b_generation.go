package control

// Strict 64-nibble lowercase SHA-256 policy; no truncated, uppercase or
// unvalidated generation can authorize a controller reload acknowledgement.
func r05bValidGeneration(s string) bool {
    if len(s) != 64 { return false }
    for _, c := range s { if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f') { return false } }
    return true
}

func r05bMatchingGeneration(expected, observed string) bool {
    return r05bValidGeneration(expected) && r05bValidGeneration(observed) && expected == observed
}
