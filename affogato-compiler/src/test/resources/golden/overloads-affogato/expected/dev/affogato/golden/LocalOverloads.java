package dev.affogato.golden;

public class LocalOverloads {
    public static String route(String name, int count) {
        return name + count;
    }

    public static String route(long count, Object name) {
        return name.toString() + count;
    }

}
