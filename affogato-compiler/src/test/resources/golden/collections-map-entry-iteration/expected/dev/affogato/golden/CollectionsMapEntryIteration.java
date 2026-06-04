package dev.affogato.golden;

import java.util.HashMap;

public class CollectionsMapEntryIteration {
    public String run() {
        final HashMap<String,Integer> values = new HashMap<String, Integer>();
        values.put("a", 1);
        values.put("b", 2);
        String text = "";
        for (var entry : values.entrySet()) {
            final String key = entry.getKey();
            final Integer value = entry.getValue();
            text = text + key + value;
        }
        return text;
    }

}
