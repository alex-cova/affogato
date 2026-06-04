package dev.affogato.golden;

public class CollectionsGenericIteration {
    public String run() {
        java.util.ArrayList<Counter<String>> counters = new java.util.ArrayList<Counter<String>>();
        counters.add(new Counter<String>("a"));
        counters.add(new Counter<String>("b"));
        String result = "";
        for (var counter : counters) {
            result = result + counter.value();
        }
        return result;
    }

}
