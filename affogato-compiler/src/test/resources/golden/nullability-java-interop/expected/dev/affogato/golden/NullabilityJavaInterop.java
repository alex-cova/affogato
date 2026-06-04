package dev.affogato.golden;

import dev.affogato.golden.interop.JavaNullabilityApi;
import dev.affogato.runtime.Nullable;
import dev.affogato.runtime.NotNull;
import java.util.Objects;

public class NullabilityJavaInterop {
    public @Nullable String maybe(boolean flag) {
        final @Nullable String value = JavaNullabilityApi.maybe(flag);
        return value;
    }

    public @NotNull String require(@NotNull String value) {
        Objects.requireNonNull(value, "value");
        return JavaNullabilityApi.require(value);
    }

}
