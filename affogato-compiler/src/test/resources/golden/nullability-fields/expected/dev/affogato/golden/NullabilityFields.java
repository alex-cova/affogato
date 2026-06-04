package dev.affogato.golden;

import dev.affogato.runtime.Nullable;
import dev.affogato.runtime.NotNull;
import java.util.Objects;

public class NullabilityFields {
    private @NotNull String required;
    private @Nullable String optional;

    public NullabilityFields(@NotNull String required, @Nullable String optional) {
        Objects.requireNonNull(required, "required");
        this.required = required;
        this.optional = optional;
    }

    public @NotNull String getRequired() {
        return required;
    }

    public void setRequired(@NotNull String value) {
        Objects.requireNonNull(value, "value");
        this.required = value;
    }

    public @Nullable String getOptional() {
        return optional;
    }

    public void setOptional(@Nullable String value) {
        this.optional = value;
    }

    public String describe() {
        System.out.println(optional);
        return required.trim();
    }

}
