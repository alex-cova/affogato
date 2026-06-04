package dev.affogato.golden;

public class ReflectionGetClass {
    public boolean isString(Object value) {
        return value.getClass() == String.class;
    }

    public String describe(Object value) {
        if (value.getClass() != String.class) {
            return "other";
        }
        return "string";
    }

}
