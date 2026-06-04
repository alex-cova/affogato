package dev.affogato.golden;

public class Switches {
    public String describe(int width) {
        return switch (width) {
            case 0 -> "empty";
            case 1 -> "thin";
            default -> "filled";
        };
    }

    public void draw(int width) {
        switch (width) {
            case 0 -> System.out.println("zero");
            default -> System.out.println("width: " + width);
        }
    }

}
