package dev.affogato.golden;

import java.util.HashMap;

public class MapOperations {
    public String run() {
        HashMap<String,Integer> values = new HashMap<String, Integer>();
        values.put("one", 1);
        values.put("two", 2);
        System.out.println(values);
        final int size = values.size();
        return "size" + size;
    }

}
