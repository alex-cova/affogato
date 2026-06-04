package dev.affogato.golden;

public class CastsNegativeBranch {
    public String describe(Object value) {
        if (value instanceof String) {
            return ((String) value);
        } else {
            return "not string";
        }
    }

}
