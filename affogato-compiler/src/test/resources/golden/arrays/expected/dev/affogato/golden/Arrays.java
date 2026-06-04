package dev.affogato.golden;

public class Arrays {
    public int sum() {
        final int[] numbers = new int[]{1, 2, 3};
        int total = 0;
        for (var number : numbers) {
            total = total + number;
        }
        return total + numbers.length;
    }

    public String[] labels() {
        return new String[]{"a", "b"};
    }

}
