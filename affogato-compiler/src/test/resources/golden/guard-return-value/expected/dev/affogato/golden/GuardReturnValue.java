package dev.affogato.golden;

public class GuardReturnValue {
    public String describe(Object value) {
        if (!(value instanceof String)) {
            return "other";
        }
        return ((String) value);
    }

}
