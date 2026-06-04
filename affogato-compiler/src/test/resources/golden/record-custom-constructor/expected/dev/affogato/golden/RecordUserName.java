package dev.affogato.golden;

import dev.affogato.runtime.NotNull;
import java.util.Objects;

public record RecordUserName(@NotNull String name) {
    public RecordUserName {
        Objects.requireNonNull(name, "name");
    }

    public String normalized() {
        return name.trim();
    }

}
