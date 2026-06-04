package dev.affogato.golden;

import java.util.List;

public class OverloadsVarargs {
    public String run() {
        final java.lang.String formatted = String.format("%s-%s-%d", "af", "fogato", 2);
        final java.util.List<String> values = List.of("one", "two", "three");
        final int size = values.size();
        return formatted + ":" + size;
    }

}
