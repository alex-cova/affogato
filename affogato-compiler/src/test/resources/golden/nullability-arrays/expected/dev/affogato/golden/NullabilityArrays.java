package dev.affogato.golden;

import dev.affogato.runtime.Nullable;
import dev.affogato.runtime.NotNull;

public class NullabilityArrays {
    public int requiredElements(@NotNull String[] values) {
        return values.length;
    }

    public int nullableArray(@Nullable String[] values) {
        if (values == null) {
            return 0;
        }
        return values.length;
    }

}
