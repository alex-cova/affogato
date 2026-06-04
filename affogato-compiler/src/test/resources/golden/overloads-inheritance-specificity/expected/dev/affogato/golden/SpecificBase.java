package dev.affogato.golden;

public class SpecificBase {
    public String choose(CharSequence value) {
        return "base-char:" + value.toString();
    }

    public String choose(Object value) {
        return "base-object:" + value.toString();
    }

}
