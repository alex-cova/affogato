package dev.affogato.golden;

import dev.affogato.runtime.Nullable;

public class SwitchNullDefault {
    public String describe(@Nullable String value) {
        return switch (value) {
            case "yes" -> "accepted";
            case "no" -> "rejected";
            default -> "unknown";
        };
    }

}
