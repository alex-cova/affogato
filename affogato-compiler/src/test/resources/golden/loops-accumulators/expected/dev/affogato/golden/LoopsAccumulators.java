package dev.affogato.golden;

public class LoopsAccumulators {
    public int sum() {
        final int[] values = new int[]{1, 2, 3, 4};
        int total = 0;
        for (var value : values) {
            total = total + value;
        }
        int index = 0;
        while (index < values.length) {
            index = index + 1;
        }
        return total + index;
    }

}
