package dev.affogato.golden;

import java.util.List;
import dev.affogato.runtime.Nullable;
import dev.affogato.runtime.NotNull;

public class NullabilityGenerics {
    public String describe(List<@NotNull String> required, List<@Nullable String> optional) {
        final int requiredSize = required.size();
        final int optionalSize = optional.size();
        return requiredSize + ":" + optionalSize;
    }

}
