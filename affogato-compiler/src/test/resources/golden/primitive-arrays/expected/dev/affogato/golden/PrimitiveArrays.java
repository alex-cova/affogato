package dev.affogato.golden;

public class PrimitiveArrays {
    public long run() {
        final boolean[] flags = new boolean[]{true, false, true};
        final long[] values = new long[]{1L, 2L, 3L};
        long total = 0L;
        for (var value : values) {
            total = total + value;
        }
        if (flags.length > 0) {
            total = total + flags.length;
        }
        return total;
    }

}
