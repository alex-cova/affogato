package dev.affogato.golden;

import java.util.List;

public class CollectionsListOf {
    public String run() {
        final java.util.List<String> values = List.of("af", "fogato", "latte");
        final String first = values.get(0);
        final int size = values.size();
        return first + ":" + size;
    }

}
