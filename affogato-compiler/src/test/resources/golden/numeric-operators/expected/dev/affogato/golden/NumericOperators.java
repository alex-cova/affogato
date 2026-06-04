package dev.affogato.golden;

public class NumericOperators {
    public long compute(int left, long right) {
        final long sum = left + right;
        final long diff = right - left;
        final long product = sum * diff;
        return product / 2L;
    }

    public boolean compare(int value) {
        return value >= 0 && value <= 10;
    }

}
