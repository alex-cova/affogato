package dev.affogato.golden;

public class BaseChoices {
    public String pick(CharSequence value) {
        return value.toString();
    }

    public String pick(Object value) {
        return value.toString();
    }

}
