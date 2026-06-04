package dev.affogato.golden;

import java.util.List;

public class GenericOverloadMethods {
    public static <T> String pick(T value) {
        return "t:" + value.toString();
    }

    public static <T> String pick(List<T> values) {
        final int size = values.size();
        return "list:" + size;
    }

    public static String pick(String value) {
        return "string:" + value;
    }

}
