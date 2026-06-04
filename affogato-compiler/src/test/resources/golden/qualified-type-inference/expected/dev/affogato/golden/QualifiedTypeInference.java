package dev.affogato.golden;

import java.util.ArrayList;
import java.util.HashMap;

public class QualifiedTypeInference {
    public String run() {
        ArrayList<String> names = new ArrayList<String>();
        names.add("affogato");
        HashMap<String,Integer> counts = new HashMap<String, Integer>();
        counts.put("shots", 2);
        final String name = names.get(0);
        final int size = counts.size();
        return name + size;
    }

}
