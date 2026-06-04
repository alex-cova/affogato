package dev.affogato.golden;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CollectionsNestedMapEntry {
    public String run() {
        final HashMap<String,Map<String,List<Integer>>> outer = new HashMap<String, Map<String, List<Integer>>>();
        final HashMap<String,List<Integer>> inner = new HashMap<String, List<Integer>>();
        inner.put("counts", List.of(Integer.valueOf(1), Integer.valueOf(2)));
        outer.put("first", inner);
        String text = "";
        for (var outerEntry : outer.entrySet()) {
            final String outerKey = outerEntry.getKey();
            final Map<String,List<Integer>> nested = outerEntry.getValue();
            for (var innerEntry : nested.entrySet()) {
                final String innerKey = innerEntry.getKey();
                final List<Integer> values = innerEntry.getValue();
                final int size = values.size();
                text = text + outerKey + ":" + innerKey + ":" + size;
            }
        }
        return text;
    }

}
