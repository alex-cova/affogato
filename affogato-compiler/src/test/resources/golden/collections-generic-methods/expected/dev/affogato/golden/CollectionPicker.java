package dev.affogato.golden;

public class CollectionPicker {
    public <T> T first(java.util.List<T> values) {
        return values.get(0);
    }

    public <T> java.util.List<T> append(java.util.List<T> values, T value) {
        values.add(value);
        return values;
    }

}
