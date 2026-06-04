package dev.affogato.golden;

public class GenericStatics {
    public static <T> T identity(T value) {
        return value;
    }

    public static <T> T choose(T first, T second) {
        return second;
    }

}
