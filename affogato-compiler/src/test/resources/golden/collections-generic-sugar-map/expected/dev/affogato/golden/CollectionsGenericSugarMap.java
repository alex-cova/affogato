package dev.affogato.golden;

public class CollectionsGenericSugarMap {
    public java.util.Map<String,java.util.List<String>> run() {
        java.util.ArrayList<String> names = new java.util.ArrayList<String>();
        names.add("cappuccino");
        java.util.HashMap<String,java.util.List<String>> lookup = new java.util.HashMap<String, java.util.List<String>>();
        lookup.put("menu", names);
        return lookup;
    }

}
