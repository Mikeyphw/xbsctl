package io.github.mikeceead.xbscompanion;

import java.util.Map;
import java.util.LinkedHashMap;
import org.json.JSONObject;

/** Typed, allowlisted command envelope for root execution. */
final class XbsCommandSpec {
    final SuiteAction action;
    final String command;
    final ExecutionSource source;
    final MutationClass mutationClass;
    final TimeoutPolicy timeout;
    final String backend;
    final String workdir;
    final String recoveryKind;
    final String recoveryData;

    private XbsCommandSpec(SuiteAction action, String command, ExecutionSource source,
                           MutationClass mutationClass, TimeoutPolicy timeout,
                           String recoveryKind, String recoveryData) {
        this.action = action;
        this.command = command;
        this.source = source == null ? ExecutionSource.UNKNOWN : source;
        this.mutationClass = mutationClass == null ? MutationClass.STATE_CHANGE : mutationClass;
        this.timeout = timeout == null ? TimeoutPolicy.forMutation(this.mutationClass) : timeout;
        this.backend = "root";
        this.workdir = "~/";
        this.recoveryKind = recoveryKind == null ? "none" : recoveryKind;
        this.recoveryData = recoveryData == null ? "" : recoveryData;
    }

    static XbsCommandSpec fromAction(SuiteAction action, String argument, ExecutionSource source) {
        if (action == null) throw new IllegalArgumentException("Action is required");
        if (!"xbs".equals(action.module)) throw new IllegalArgumentException("Only XBS actions are allowed");
        SuiteAction allowlisted = SuiteCatalog.find(action.module, action.id);
        if (allowlisted != null && allowlisted != action) {
            throw new IllegalArgumentException("Suite catalog action instance does not match the allowlist");
        }
        String safeArgument = SuiteAction.validateArgument(argument, action.needsArgument);
        String built = action.command(safeArgument);
        String trimmed = built == null ? "" : built.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("Command is empty");
        MutationClass mutation = MutationClass.from(action);
        String kind = allowlisted == action ? "suite" : "none";
        String data = allowlisted == action ? safeArgument : "";
        return new XbsCommandSpec(action, trimmed, source, mutation, TimeoutPolicy.forMutation(mutation), kind, data);
    }

    static XbsCommandSpec fromCatalog(CliCommand command, Map<String,String> values, ExecutionSource source) {
        if (command == null) throw new IllegalArgumentException("Catalog command is required");
        CliCommand allowlisted = CommandCenterPolicy.requireAllowlisted(command.id);
        if (allowlisted != command) {
            throw new IllegalArgumentException("Catalog command instance does not match the allowlist");
        }
        if (allowlisted.isNative()) throw new IllegalArgumentException("Native screens are not root commands");
        if (CommandCenterPolicy.isAndroidBlocked(allowlisted.id)) {
            throw new IllegalArgumentException("This command is not supported from Android");
        }
        Map<String,String> supplied = values == null ? java.util.Collections.emptyMap() : values;
        Map<String,String> persisted = new LinkedHashMap<>();
        for (CliField field : allowlisted.fields) {
            if (!supplied.containsKey(field.name)) continue;
            persisted.put(field.name, field.validate(supplied.get(field.name)));
        }
        String generated = allowlisted.build(supplied).trim();
        if (generated.isEmpty()) throw new IllegalArgumentException("Generated command is empty");
        MutationClass mutation = MutationClass.from(allowlisted);
        return new XbsCommandSpec(SuiteAction.catalog(allowlisted, generated), generated, source, mutation,
            TimeoutPolicy.forMutation(mutation), "catalog", new JSONObject(persisted).toString());
    }

    static XbsCommandSpec recoverPersisted(String id, String command, ExecutionSource source, String kind, String data) {
        XbsCommandSpec recovered;
        if ("suite".equals(kind)) {
            SuiteAction action = SuiteCatalog.find("xbs", id);
            if (action == null) throw new IllegalArgumentException("Persisted suite action is no longer allowlisted");
            recovered = fromAction(action, data, source);
        } else if ("catalog".equals(kind)) {
            CliCommand catalog = CommandCenterPolicy.requireAllowlisted(id);
            Map<String,String> values = new LinkedHashMap<>();
            try {
                JSONObject object = new JSONObject(data == null || data.trim().isEmpty() ? "{}" : data);
                java.util.Iterator<String> keys = object.keys();
                java.util.Set<String> allowed = new java.util.HashSet<>();
                for (CliField field : catalog.fields) allowed.add(field.name);
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown persisted command field");
                    Object raw = object.opt(key);
                    if (!(raw instanceof String)) throw new IllegalArgumentException("Persisted command field is not a string");
                    values.put(key, (String)raw);
                }
            } catch (org.json.JSONException error) {
                throw new IllegalArgumentException("Persisted command values are invalid", error);
            }
            recovered = fromCatalog(catalog, values, source);
        } else {
            throw new IllegalArgumentException("Persisted command is not safely recoverable");
        }
        String expected = command == null ? "" : command.trim();
        if (!recovered.command.equals(expected)) throw new IllegalArgumentException("Persisted command no longer matches the current allowlist");
        return recovered;
    }

    String startMessage() {
        return "Running " + mutationClass.label.replace('_', ' ') + " command from "
            + source.label + " with " + backend + " · timeout " + timeout.seconds + "s";
    }

}
