package io.github.mikeceead.xbscompanion;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Coarse mutation policy used by the bounded foreground command service. */
enum MutationClass {
    READ_ONLY("read_only"),
    STATE_CHANGE("state_change"),
    DESTRUCTIVE("destructive");

    private static final Set<String> READ_ONLY_ACTIONS = new HashSet<>(Arrays.asList(
        "status", "event_list", "validate"
    ));

    final String label;

    MutationClass(String label) { this.label = label; }

    static MutationClass from(SuiteAction action) {
        if (action == null) return STATE_CHANGE;
        if (action.destructive) return DESTRUCTIVE;
        if (READ_ONLY_ACTIONS.contains(action.id)) return READ_ONLY;
        return STATE_CHANGE;
    }

    static MutationClass from(CliCommand command) {
        return CommandCenterPolicy.mutation(command);
    }
}
