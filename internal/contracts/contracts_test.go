package contracts

import "testing"

func TestFrozenContractsValidate(t *testing.T) {
	summary, err := Check("../..")
	if err != nil {
		t.Fatalf("Check: %v", err)
	}
	if summary.CLICommands != 17 {
		t.Fatalf("CLICommands = %d", summary.CLICommands)
	}
	if summary.BridgeCommands != 39 {
		t.Fatalf("BridgeCommands = %d", summary.BridgeCommands)
	}
	if summary.XBSctlCommands != 11 {
		t.Fatalf("XBSctlCommands = %d", summary.XBSctlCommands)
	}
	if summary.EventBridgeFormat != 1 {
		t.Fatalf("EventBridgeFormat = %d", summary.EventBridgeFormat)
	}
}

func TestProductionCommandsAreDisabledInSkeleton(t *testing.T) {
	if err := ValidateRefusesProduction([]string{"status"}); err == nil {
		t.Fatal("status must stay disabled until Go parity phases implement it")
	}
	if err := ValidateRefusesProduction([]string{"contracts", "check"}); err != nil {
		t.Fatalf("contracts check must remain allowed: %v", err)
	}
}
