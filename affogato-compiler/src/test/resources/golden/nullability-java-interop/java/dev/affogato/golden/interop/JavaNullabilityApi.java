package dev.affogato.golden.interop;

import dev.affogato.runtime.NotNull;
import dev.affogato.runtime.Nullable;

public final class JavaNullabilityApi {
    private JavaNullabilityApi() {
    }

    public static @Nullable String maybe(boolean flag) {
        return flag ? "affogato" : null;
    }

    public static @NotNull String require(@NotNull String value) {
        return value.trim();
    }
}
