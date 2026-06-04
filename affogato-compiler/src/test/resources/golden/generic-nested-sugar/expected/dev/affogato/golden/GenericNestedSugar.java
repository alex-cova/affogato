package dev.affogato.golden;

public class GenericNestedSugar {
    public java.util.List<NestedSugarBox<String>> run() {
        java.util.ArrayList<NestedSugarBox<String>> boxes = new java.util.ArrayList<NestedSugarBox<String>>();
        boxes.add(new NestedSugarBox<String>("cortado"));
        return boxes;
    }

    public java.util.Map<String,java.util.List<NestedSugarBox<Integer>>> lookup() {
        java.util.ArrayList<NestedSugarBox<Integer>> numbers = new java.util.ArrayList<NestedSugarBox<Integer>>();
        numbers.add(new NestedSugarBox<Integer>(3));
        java.util.HashMap<String,java.util.List<NestedSugarBox<Integer>>> lookup = new java.util.HashMap<String, java.util.List<NestedSugarBox<Integer>>>();
        lookup.put("shots", numbers);
        return lookup;
    }

}
