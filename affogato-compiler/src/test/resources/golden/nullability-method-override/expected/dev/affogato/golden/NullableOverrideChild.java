package dev.affogato.golden;

import dev.affogato.runtime.Nullable;
import dev.affogato.runtime.NotNull;
import java.util.Objects;

public class NullableOverrideChild extends NullableOverrideBase {
    @Override
    public @Nullable String label(@NotNull String value) {
        Objects.requireNonNull(value, "value");
        return value.trim();
    }

}
