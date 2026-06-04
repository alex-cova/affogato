package dev.affogato.golden;

public class SwitchLocalExpression {
    public String describe(int width) {
        final var label = switch (width) {
            case 0 -> "empty";
            case 1 -> "thin";
            default -> "filled";
        };
        return "width:" + label;
    }

}
