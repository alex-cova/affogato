package dev.affogato.golden;

import java.util.Arrays;
import java.util.List;

public class JavaVarargs {
    public String run() {
        final java.lang.String formatted = String.format("%s-%s-%d", "af", "fogato", 2);
        final java.util.List<String> values = List.of("one", "two");
        final java.util.List<String> arrayList = Arrays.asList("three", "four");
        final int valuesSize = values.size();
        final int arrayListSize = arrayList.size();
        return formatted + ":" + valuesSize + ":" + arrayListSize;
    }

}
