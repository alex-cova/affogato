package dev.affogato.golden;

import java.util.Arrays;

public class CollectionsArraysAsList {
    public String run() {
        final java.util.List<String> values = Arrays.asList("a", "b");
        final String first = values.get(0);
        final int size = values.size();
        return first + ":" + size;
    }

}
