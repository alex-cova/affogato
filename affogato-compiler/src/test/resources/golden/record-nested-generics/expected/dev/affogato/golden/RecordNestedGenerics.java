package dev.affogato.golden;

import java.util.List;

public class RecordNestedGenerics {
    public String run() {
        final Page<String> page = new Page<String>(List.of("af", "fogato"));
        final String first = page.first();
        final int size = page.size();
        return first + ":" + size;
    }

}
