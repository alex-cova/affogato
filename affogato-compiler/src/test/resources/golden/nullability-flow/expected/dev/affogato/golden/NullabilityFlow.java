package dev.affogato.golden;

import dev.affogato.runtime.Nullable;
import dev.affogato.runtime.NotNull;
import java.util.Objects;

public class NullabilityFlow {
    public String require(@NotNull String value) {
        Objects.requireNonNull(value, "value");
        return value.trim();
    }

    public @Nullable String maybe(boolean flag) {
        if (flag) {
            return "ok";
        }
        return null;
    }

    public String run() {
        final @NotNull String value = "ready";
        final @Nullable String optional = null;
        System.out.println(optional);
        return require(value);
    }

}
