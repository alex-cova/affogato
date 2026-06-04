package dev.affogato.golden;

import dev.affogato.runtime.Nullable;

public class NullableTernary {
    public @Nullable String choose(boolean flag) {
        final @Nullable String value = flag ? "ok" : null;
        return value;
    }

}
