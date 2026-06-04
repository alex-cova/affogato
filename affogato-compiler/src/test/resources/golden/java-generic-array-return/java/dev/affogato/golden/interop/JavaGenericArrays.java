package dev.affogato.golden.interop;

public final class JavaGenericArrays {
    private JavaGenericArrays() {
    }

    @SafeVarargs
    public static <T> T[] arrayOf(T... values) {
        return values;
    }
}
