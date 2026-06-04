package dev.affogato.golden;

public class NestedSugarBox<T> {
    private T value;

    public NestedSugarBox(T value) {
        this.value = value;
    }

    public T getValue() {
        return value;
    }

    public void setValue(T value) {
        this.value = value;
    }

}
