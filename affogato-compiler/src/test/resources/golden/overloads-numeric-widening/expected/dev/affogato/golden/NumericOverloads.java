package dev.affogato.golden;

public class NumericOverloads {
    public static String widen(long value) {
        return "long" + value;
    }

    public static String widen(double value) {
        return "double" + value;
    }

    public static String boxed(Integer value) {
        return "boxed" + value;
    }

    public static String unboxed(int value) {
        return "unboxed" + value;
    }

}
