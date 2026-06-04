package dev.affogato.golden;

public class InterpolationRich {
    public String describe(String name, int count) {
        final String label = "Order " + (name) + ": " + (count) + " items";
        return "" + (label) + " / next " + (count + 1);
    }

}
