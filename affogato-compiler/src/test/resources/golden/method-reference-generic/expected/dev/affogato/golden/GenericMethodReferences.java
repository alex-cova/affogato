package dev.affogato.golden;

import java.util.function.Function;

public class GenericMethodReferences {
    public static <T> T identity(T value) {
        return value;
    }

}
