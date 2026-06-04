package dev.affogato.golden;

import dev.affogato.runtime.Nullable;
import dev.affogato.runtime.NotNull;
import java.util.Objects;

public class NullabilityConstructorFields {
    private @NotNull String name;
    private final @Nullable String alias;

    public NullabilityConstructorFields(@NotNull String name, @Nullable String alias) {
        Objects.requireNonNull(name, "name");
        this.name = name;
        this.alias = alias;
    }

    public @NotNull String getName() {
        return name;
    }

    public void setName(@NotNull String value) {
        Objects.requireNonNull(value, "value");
        this.name = value;
    }

    public @Nullable String getAlias() {
        return alias;
    }

    public String display() {
        return name.trim();
    }

}
