package dev.affogato.golden;

public class GenericBoundedMethods {
    public <T extends CharSequence> T pickText(T left, T right) {
        System.out.println(right);
        return left;
    }

    public <T extends Number> T pickNumber(T left, T right) {
        System.out.println(right);
        return left;
    }

    public int run() {
        final String text = pickText("affogato", "latte");
        final Integer number = pickNumber(7, 9);
        System.out.println(number);
        return text.length();
    }

}
