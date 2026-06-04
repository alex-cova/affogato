package dev.affogato.golden;

public class StringEscapes {
    public String backslash() {
        return "\\";
    }

    public String quote() {
        return "say \"hi\"";
    }

    public String newline() {
        return "line1\nline2";
    }

    public String tab() {
        return "col1\tcol2";
    }

    public String terminatedByBackslash() {
        return "ends\\";
    }

    public String mixedEscapes() {
        return "a\\b\"c\nd";
    }

}
