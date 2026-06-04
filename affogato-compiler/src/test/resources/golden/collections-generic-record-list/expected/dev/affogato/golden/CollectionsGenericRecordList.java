package dev.affogato.golden;

public class CollectionsGenericRecordList {
    public String run() {
        java.util.ArrayList<MenuItem<Integer>> items = new java.util.ArrayList<MenuItem<Integer>>();
        items.add(new MenuItem<Integer>("espresso", 2));
        items.add(new MenuItem<Integer>("latte", 3));
        final MenuItem<Integer> first = items.get(0);
        return first.name() + first.value();
    }

}
