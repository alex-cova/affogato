package dev.affogato.golden;

public class StaticOverloadSource {
    public static String pick(String value) {
        return "string:" + value;
    }

    public static String pick(int value) {
        return "int:" + value;
    }

}
