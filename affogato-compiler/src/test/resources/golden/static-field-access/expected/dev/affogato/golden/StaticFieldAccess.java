package dev.affogato.golden;

public class StaticFieldAccess {
    public String run() {
        StaticFieldSource.counter = StaticFieldSource.counter + 2;
        final String label = StaticFieldSource.DEFAULT_LABEL;
        final int count = StaticFieldSource.counter;
        return label + ":" + count;
    }

}
