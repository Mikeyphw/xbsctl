package io.github.mikeceead.xbscompanion;

final class SuiteAction {
    static final int MAX_ARGUMENT_CHARS = 96;
    private static final String SAFE_ARGUMENT_PATTERN = "[A-Za-z0-9_.:-]+";

    final String module, id, title, description, commandTemplate, defaultBackend;
    final boolean needsArgument, destructive;

    SuiteAction(String module,String id,String title,String description,String commandTemplate,String defaultBackend,boolean needsArgument,boolean destructive){
        this.module=module;this.id=id;this.title=title;this.description=description;this.commandTemplate=commandTemplate;
        this.defaultBackend=defaultBackend;this.needsArgument=needsArgument;this.destructive=destructive;
    }

    String command(String argument){
        String safe = validateArgument(argument, needsArgument);
        return commandTemplate.replace("{arg}", shellQuote(safe));
    }

    static String validateArgument(String argument, boolean required) {
        String safe = argument == null ? "" : argument.trim();
        if (required && safe.isEmpty()) throw new IllegalArgumentException("This action requires a value");
        if (safe.isEmpty()) return safe;
        if (safe.indexOf('\0') >= 0 || safe.indexOf('\n') >= 0 || safe.indexOf('\r') >= 0 || safe.indexOf('\t') >= 0) {
            throw new IllegalArgumentException("Action argument must fit on one shell token line");
        }
        if (safe.length() > MAX_ARGUMENT_CHARS) throw new IllegalArgumentException("Action argument is too long");
        if (!safe.matches(SAFE_ARGUMENT_PATTERN)) throw new IllegalArgumentException("Action argument contains unsupported characters");
        return safe;
    }

    static SuiteAction custom(String id,String title,String description,String commandTemplate,boolean destructive){
        return new SuiteAction("xbs",id,title,description,commandTemplate,"root",false,destructive);
    }

    static SuiteAction catalog(CliCommand command, String generatedCommand) {
        if (command == null) throw new IllegalArgumentException("Catalog command is required");
        return new SuiteAction("xbs", command.id, command.title, command.description,
            generatedCommand, "root", false, command.destructive);
    }

    static String shellQuote(String value){return "'"+value.replace("'","'\\''")+"'";}
}
