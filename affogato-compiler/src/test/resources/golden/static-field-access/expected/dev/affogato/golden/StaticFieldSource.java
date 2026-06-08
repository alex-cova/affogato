package dev.affogato.golden;

public class StaticFieldSource {
    public static final String DEFAULT_LABEL = "static";
    public static int counter = 1;

    public static String getDEFAULT_LABEL() {
        return DEFAULT_LABEL;
    }

    public static int getCounter() {
        return counter;
    }

    public static void setCounter(int value) {
        counter = value;
    }

}
