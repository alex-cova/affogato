package dev.affogato.golden;

public class TernaryNumeric {
    public long choose(boolean flag) {
        final long value = flag ? 1 : 2L;
        return value;
    }

    public String label(int count) {
        return count > 0 ? "positive" : "zero";
    }

}
