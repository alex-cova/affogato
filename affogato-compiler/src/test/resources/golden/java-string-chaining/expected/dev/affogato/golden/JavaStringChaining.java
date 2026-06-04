package dev.affogato.golden;

public class JavaStringChaining {
    public String run(String value) {
        final java.lang.String cleaned = value.trim().toUpperCase();
        final java.lang.String prefix = cleaned.substring(0, 1);
        return prefix + cleaned.length();
    }

}
