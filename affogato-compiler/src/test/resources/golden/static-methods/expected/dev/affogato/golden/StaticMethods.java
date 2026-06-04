package dev.affogato.golden;

public class StaticMethods {
    public static String prefix(String value) {
        return "x-" + value;
    }

    public static int add(int left, int right) {
        return left + right;
    }

    public String run() {
        final String label = StaticMethods.prefix("id");
        final int total = StaticMethods.add(1, 2);
        return label + total;
    }

}
