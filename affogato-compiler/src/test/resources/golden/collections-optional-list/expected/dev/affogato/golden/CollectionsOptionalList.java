package dev.affogato.golden;

import java.util.List;
import java.util.Optional;

public class CollectionsOptionalList {
    public String run() {
        final java.util.Optional<java.util.List<String>> maybe = Optional.of(List.of("af", "fogato"));
        final java.util.List<String> values = maybe.orElse(List.of("empty"));
        final String first = values.get(0);
        final int size = values.size();
        return first + ":" + size;
    }

}
