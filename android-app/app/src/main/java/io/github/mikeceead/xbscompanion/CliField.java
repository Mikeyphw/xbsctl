package io.github.mikeceead.xbscompanion;

import java.util.Arrays;

final class CliField {
    enum Kind { TEXT, INTEGER, BOOLEAN, CHOICE, MULTILINE, WORDS }
    private static final String SAFE_EVENT_PATTERN = "[A-Za-z0-9_.:-]+";
    private static final String SAFE_DOTTED_KEY_PATTERN = "[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*";
    final String name, label, flag, hint, defaultValue;
    final Kind kind;
    final boolean required;
    final String[] choices;

    private CliField(String name, String label, String flag, String hint, String defaultValue,
                     Kind kind, boolean required, String[] choices) {
        this.name = name;
        this.label = label;
        this.flag = flag;
        this.hint = hint;
        this.defaultValue = defaultValue;
        this.kind = kind;
        this.required = required;
        this.choices = choices == null ? new String[0] : choices.clone();
    }

    String validate(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (required && value.isEmpty()) throw new IllegalArgumentException(label + " is required");
        if (value.indexOf('\0') >= 0) throw new IllegalArgumentException(label + " contains an invalid NUL character");
        if (kind != Kind.MULTILINE && (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)) {
            throw new IllegalArgumentException(label + " must fit on one line");
        }
        int max = fieldMax();
        if (value.length() > max) throw new IllegalArgumentException(label + " is too long");
        if (value.isEmpty()) return value;
        validateNamedField(value);

        if (kind == Kind.BOOLEAN) {
            if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                throw new IllegalArgumentException(label + " must be enabled or disabled");
            }
            return value.toLowerCase(java.util.Locale.ROOT);
        }
        if (kind == Kind.CHOICE) {
            for (String choice : choices) if (choice.equals(value)) return value;
            throw new IllegalArgumentException(label + " must be one of " + Arrays.toString(choices));
        }
        if (kind == Kind.INTEGER) {
            final int number;
            try { number = Integer.parseInt(value); }
            catch (NumberFormatException error) { throw new IllegalArgumentException(label + " must be an integer"); }
            if ("timeout".equals(name) && (number < 1 || number > TimeoutPolicy.MAX_SECONDS)) {
                throw new IllegalArgumentException(label + " must be between 1 and " + TimeoutPolicy.MAX_SECONDS);
            }
            if ("index".equals(name) && (number < 0 || number > 256)) {
                throw new IllegalArgumentException(label + " must be between 0 and 256");
            }
            return Integer.toString(number);
        }
        if (kind == Kind.WORDS) {
            String[] words = value.split("\\s+");
            if (words.length > 64) throw new IllegalArgumentException(label + " accepts at most 64 words");
        }
        return value;
    }

    private int fieldMax() {
        if ("event".equals(name)) return 96;
        if ("key".equals(name)) return 160;
        return kind == Kind.MULTILINE ? 16384 : kind == Kind.WORDS ? 2048 : 1024;
    }

    private void validateNamedField(String value) {
        if ("event".equals(name) && !value.matches(SAFE_EVENT_PATTERN)) {
            throw new IllegalArgumentException(label + " contains unsupported event-name characters");
        }
        if ("key".equals(name) && !value.matches(SAFE_DOTTED_KEY_PATTERN)) {
            throw new IllegalArgumentException(label + " must be a dotted ASCII configuration key");
        }
    }

    static CliField text(String name,String label,boolean required,String hint){return new CliField(name,label,"",hint,"",Kind.TEXT,required,null);}
    static CliField textDefault(String name,String label,boolean required,String hint,String def){return new CliField(name,label,"",hint,def,Kind.TEXT,required,null);}
    static CliField multiline(String name,String label,boolean required,String hint){return new CliField(name,label,"",hint,"",Kind.MULTILINE,required,null);}
    static CliField integer(String name,String label,boolean required,String hint,String def){return new CliField(name,label,"",hint,def,Kind.INTEGER,required,null);}
    static CliField integerFlag(String name,String label,String flag,String def){return new CliField(name,label,flag,"",def,Kind.INTEGER,false,null);}
    static CliField textFlag(String name,String label,String flag,String def){return new CliField(name,label,flag,"",def,Kind.TEXT,false,null);}
    static CliField words(String name,String label,boolean required,String hint){return new CliField(name,label,"",hint,"",Kind.WORDS,required,null);}
    static CliField wordsFlag(String name,String label,String flag,String hint){return new CliField(name,label,flag,hint,"",Kind.WORDS,false,null);}
    static CliField boolFlag(String name,String label,String flag){return boolFlagDefault(name,label,flag,false);}
    static CliField boolFlagDefault(String name,String label,String flag,boolean def){return new CliField(name,label,flag,"",String.valueOf(def),Kind.BOOLEAN,false,null);}
    static CliField choice(String name,String label,boolean required,String... choices){return new CliField(name,label,"","",choices.length>0?choices[0]:"",Kind.CHOICE,required,choices);}
    static CliField choiceFlag(String name,String label,String flag,String def,String... choices){return new CliField(name,label,flag,"",def,Kind.CHOICE,false,choices);}
}
