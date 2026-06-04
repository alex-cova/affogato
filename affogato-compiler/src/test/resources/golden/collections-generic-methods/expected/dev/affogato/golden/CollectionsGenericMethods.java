package dev.affogato.golden;

public class CollectionsGenericMethods {
    public String run() {
        final CollectionPicker picker = new CollectionPicker();
        java.util.ArrayList<String> names = new java.util.ArrayList<String>();
        picker.append(names, "ristretto");
        final String first = picker.first(names);
        return first;
    }

}
