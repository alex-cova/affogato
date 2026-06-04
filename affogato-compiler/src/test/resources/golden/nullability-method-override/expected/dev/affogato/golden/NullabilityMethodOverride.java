package dev.affogato.golden;

import dev.affogato.runtime.Nullable;

public class NullabilityMethodOverride {
    public @Nullable String run() {
        final NullableOverrideChild child = new NullableOverrideChild();
        return child.label("affogato");
    }

}
