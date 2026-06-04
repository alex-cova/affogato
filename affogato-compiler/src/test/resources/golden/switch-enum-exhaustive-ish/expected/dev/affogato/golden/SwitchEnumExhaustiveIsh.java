package dev.affogato.golden;

public class SwitchEnumExhaustiveIsh {
    public String describe(ControlState state) {
        return switch (state) {
            case ControlState.NEW -> "new";
            case ControlState.RUNNING -> "running";
            case ControlState.DONE -> "done";
        };
    }

}
