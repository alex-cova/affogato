package dev.affogato.golden.interop;

import java.util.HashMap;
import java.util.Map;

public final class CollectionWildcardFixtures {
    private CollectionWildcardFixtures() {
    }

    public static Map<String, ? extends Number> numberMap() {
        Map<String, Integer> values = new HashMap<>();
        values.put("one", 1);
        return values;
    }

    public static Number firstNumber(Map<String, ? extends Number> values) {
        return values.get("one");
    }
}
