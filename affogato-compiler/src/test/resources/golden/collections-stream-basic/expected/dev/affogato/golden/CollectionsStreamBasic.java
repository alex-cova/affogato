package dev.affogato.golden;

import java.util.List;

public class CollectionsStreamBasic {
    public String run() {
        final java.util.List<String> values = List.of("af", "fogato");
        final java.util.List mapped = values.stream().map(value -> value.toUpperCase()).toList();
        final java.lang.Object first = mapped.get(0);
        final int size = mapped.size();
        return first + ":" + size;
    }

}
