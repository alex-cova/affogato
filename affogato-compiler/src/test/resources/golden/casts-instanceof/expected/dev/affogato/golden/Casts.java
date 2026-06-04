package dev.affogato.golden;

public class Casts {
    public String describe(Object value) {
        if (value instanceof String) {
            return ((String) value);
        }
        return "other";
    }

}
