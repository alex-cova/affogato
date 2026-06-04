package dev.affogato.golden;

public class NullOverloads {
    public static String pick(String value) {
        return "string";
    }

    public static String pick(Object value) {
        return "object";
    }

}
