package dev.affogato.golden;

public class CollectionsNestedUsage {
    public void run() {
        java.util.ArrayList<String> names = new java.util.ArrayList<String>();
        names.add("espresso");
        java.util.ArrayList<java.util.List<String>> rows = new java.util.ArrayList<java.util.List<String>>();
        rows.add(names);
        java.util.HashMap<String,java.util.List<Integer>> lookup = new java.util.HashMap<String, java.util.List<Integer>>();
        System.out.println(rows);
        System.out.println(lookup);
    }

}
