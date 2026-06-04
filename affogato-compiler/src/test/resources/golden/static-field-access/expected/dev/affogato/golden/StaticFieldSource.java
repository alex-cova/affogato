package dev.affogato.golden;

public class StaticFieldSource {
    public static final String DEFAULT_LABEL = "static";
    public static int counter = 1;

    public String getDEFAULT_LABEL() {
        return DEFAULT_LABEL;
    }

    public int getCounter() {
        return counter;
    }

    public void setCounter(int value) {
        this.counter = value;
    }

}
