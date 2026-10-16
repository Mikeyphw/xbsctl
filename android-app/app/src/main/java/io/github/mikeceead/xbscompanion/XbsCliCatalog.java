package io.github.mikeceead.xbscompanion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashSet;

final class XbsCliCatalog {
    private static final List<CliCommand> COMMANDS=Collections.unmodifiableList(Arrays.asList(
        c("help","General","XBS help","Show the complete xbs command-line help.","xbs",p("--help"),false),
        c("status","General","Runtime status","Show current XBS runtime state.","xbs",p("status"),false),
        c("init","General","Initialize XBS","Seed missing config and Tasker assets.","xbs",p("init"),false),
        c("paths","General","Resolved paths","Show active config, logs, lists and runtime paths.","xbs",p("paths"),false),
        c("capabilities","General","Compatibility contract","Read module, config, command, and event-field capabilities.","xbs",p("capabilities"),false),
        c("validate","General","Validate configuration","Validate the current config or optional JSON payload.","xbs",p("validate"),false,
            CliField.multiline("json","JSON payload",false,"Leave blank to validate the active config")),

        c("config_get","Configuration","Read configuration","Read the complete config or one dotted key.","xbs",p("config","get"),false,
            CliField.text("key","Dotted key",false,"Example: header.delay")),
        c("config_set","Configuration","Set configuration value","Write a dotted config key using the CLI.","xbs",p("config","set"),false,
            CliField.text("key","Dotted key",true,"Example: header.delay"),CliField.text("value","Value",true,"JSON-like values are accepted")),
        c("config_validate","Configuration","Validate JSON payload","Validate a draft without saving it.","xbs",p("config","validate"),false,
            CliField.multiline("json","JSON payload",false,"Leave blank for current config")),
        c("config_edit_cli","Configuration","CLI configuration editor","Run the literal xbs config edit operation.","xbs",p("config","edit"),false,
            CliField.textFlag("editor","Editor command","--editor",""),CliField.boolFlag("print_path","Print config path only","--print-path")),
        special("config_native","Configuration","Native configuration editor","Edit header settings and events using native controls.","config"),
        c("config_schema","Configuration","Configuration schema","Show the config schema.","xbs",p("config","schema"),false),

        c("runtime_reload","Runtime","Reload runtime","Reload XBS after configuration changes.","xbs",p("runtime","reload"),false),
        c("runtime_pause","Runtime","Pause runtime","Pause automatic XBS processing.","xbs",p("runtime","pause"),false),
        c("runtime_resume","Runtime","Resume runtime","Resume XBS or leave safe mode.","xbs",p("runtime","resume"),false),
        c("runtime_safe","Runtime","Set safe mode","Enter or exit safe mode.","xbs",p("runtime","safe"),false,
            CliField.text("mode","Mode",false,"on/off; blank defaults to on")),

        c("event_list","Events","List events","List the standard CLI event keys.","xbs",p("event","list"),false),
        c("event_run","Events","Start standard event","Run a standard event; blank uses the CLI default.","xbs",p("event","run"),false,
            CliField.text("event","Event",false,"boot, charging, screen_off, low_power, manual; blank uses default")),
        c("event_stop","Events","Stop standard event","Stop an event; blank uses the CLI default.","xbs",p("event","stop"),true,
            CliField.text("event","Event",false,"boot, charging, screen_off, low_power, manual; blank uses default")),
        special("event_manage","Events","Manage events","Create, clone, edit and delete event profiles.","events"),

        ctl("ctl_help","Controller","Controller help","Show complete XBSctl help.",p("--help"),false),
        ctlEvent("ctl_start","Controller","Start event","Start manual, standard or custom event. Blank defaults to manual.",p("start"),false),
        ctlEvent("ctl_stop","Controller","Stop event","Stop manual, standard or custom event. Blank defaults to manual.",p("stop"),true),
        ctlWait("ctl_reload","Controller","Reload controller","Reload the config file through XBSctl.",p("reload"),false),
        ctlWait("ctl_pause","Controller","Pause controller","Pause the daemon through XBSctl.",p("pause"),false),
        ctlWait("ctl_resume","Controller","Resume controller","Resume the daemon or exit safe mode.",p("resume"),false),
        ctlWait("ctl_safe","Controller","Enter safe mode","Enter safe mode through XBSctl.",p("safe"),false),
        ctl("ctl_active","Controller","Active events","Read active events directly from XBSctl.",p("active"),false),
        ctl("ctl_status","Controller","Controller status","Read XBSctl summary.",p("status"),false),
        ctl("ctl_detail","Controller","Detailed controller status","Show CPU, Wi-Fi, Doze and runtime detail.",p("detail"),false),
        ctl("ctl_validate","Controller","Controller validation","Validate config paths as the runtime sees them.",p("validate"),false),

        c("schema_root","Developer","Complete schema","Show the complete shared CLI schema.","xbs",p("schema"),false),
        c("schema_commands","Developer","Command schema","List every published XBS command.","xbs",p("schema","commands"),false),
        c("schema_command","Developer","Inspect command schema","Inspect one command path.","xbs",p("schema","command"),false,
            CliField.words("path","Command path",false,"Example: runtime safe")),
        c("schema_output","Developer","Inspect output schema","Inspect output for a command path.","xbs",p("schema","output"),false,
            CliField.text("path","Command path",false,"Example: status")),
        c("schema_config","Developer","Inspect config key","Inspect all config keys or one dotted key.","xbs",p("schema","config"),false,
            CliField.text("key","Dotted key",false,"Example: header.notify")),
        c("completion_zsh","Developer","Generate Zsh completion","Print generated completion text.","xbs",p("completion","zsh"),false),
        c("completion_install","Developer","Install Zsh completion","Install completion in a selected directory.","xbs",p("completion","install"),false,
            CliField.choiceFlag("shell","Shell","--shell","zsh","zsh"),CliField.textFlag("directory","Directory","--directory","")),
        c("completion_complete_zsh","Developer","Resolve Zsh completion","Run the hidden completion endpoint used by shell integrations.","xbs",p("completion","complete-zsh"),false,
            CliField.wordsFlag("words","Command words","--words","Example: xbs runtime"),CliField.integerFlag("index","Cursor word index","--index","1")),
        c("manpage_show","Developer","Show manpage","Print the generated manpage.","xbs",p("manpage","show"),false),
        c("manpage_install","Developer","Install manpage","Install the generated manpage.","xbs",p("manpage","install"),false,
            CliField.textFlag("directory","Directory","--directory",""))
    ));
    private XbsCliCatalog(){}
    static List<CliCommand> all(){return COMMANDS;}
    static CliCommand find(String id){for(CliCommand command:COMMANDS)if(command.id.equals(id))return command;return null;}
    static List<CliCommand> runnable(){List<CliCommand> out=new ArrayList<>();for(CliCommand c:COMMANDS)if(c.special.isEmpty())out.add(c);return out;}
    static List<String> categories(){LinkedHashSet<String> values=new LinkedHashSet<>();for(CliCommand c:COMMANDS)values.add(c.category);return new ArrayList<>(values);}
    private static String[] p(String...parts){return parts;}
    private static CliField[] xbsFields(CliField...fields){
        CliField[] out=new CliField[fields.length+3];System.arraycopy(fields,0,out,0,fields.length);
        out[fields.length]=CliField.boolFlagDefault(CliCommand.GLOBAL_JSON,"Structured JSON output","--json",true);
        out[fields.length+1]=CliField.boolFlag(CliCommand.GLOBAL_QUIET,"Quiet output","--quiet");
        out[fields.length+2]=CliField.boolFlag(CliCommand.GLOBAL_NO_INTERACTIVE,"Disable interactive flows","--no-interactive");
        return out;
    }
    private static CliField[] ctlFields(CliField...fields){
        CliField[] out=new CliField[fields.length+1];System.arraycopy(fields,0,out,0,fields.length);
        out[fields.length]=CliField.boolFlagDefault(CliCommand.GLOBAL_JSON,"Structured JSON output","--json",true);return out;
    }
    private static CliCommand c(String id,String category,String title,String description,String exe,String[] path,boolean destructive,CliField...fields){return new CliCommand(id,category,title,description,exe,path,destructive,"",xbsFields(fields));}
    private static CliCommand ctl(String id,String category,String title,String description,String[] path,boolean destructive,CliField...fields){return new CliCommand(id,category,title,description,"XBSctl",path,destructive,"",ctlFields(fields));}
    private static CliCommand ctlWait(String id,String category,String title,String description,String[] path,boolean destructive){return ctl(id,category,title,description,path,destructive,CliField.boolFlag("wait","Wait for state transition","--wait"),CliField.integerFlag("timeout","Timeout seconds (blank uses 8)","--timeout",""));}
    private static CliCommand ctlEvent(String id,String category,String title,String description,String[] path,boolean destructive){return ctl(id,category,title,description,path,destructive,CliField.text("event","Event name",false,"Blank defaults to manual"),CliField.boolFlag("wait","Wait for state transition","--wait"),CliField.integerFlag("timeout","Timeout seconds (blank uses 8)","--timeout",""));}
    private static CliCommand special(String id,String category,String title,String description,String special){return new CliCommand(id,category,title,description,"",new String[0],false,special);}
}
