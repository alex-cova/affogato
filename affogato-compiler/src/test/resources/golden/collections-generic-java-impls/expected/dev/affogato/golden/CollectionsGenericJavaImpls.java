package dev.affogato.golden;

import java.util.ArrayList;
import java.util.LinkedHashMap;

public class CollectionsGenericJavaImpls {
    public String run() {
        ArrayList<String> names = new ArrayList<String>();
        names.add("flat");
        names.add("white");
        LinkedHashMap<String,java.util.List<String>> lookup = new LinkedHashMap<String, java.util.List<String>>();
        lookup.put("drinks", names);
        final java.util.List<String> stored = lookup.get("drinks");
        final String first = stored.get(0);
        final String second = stored.get(1);
        return first + second;
    }

}
