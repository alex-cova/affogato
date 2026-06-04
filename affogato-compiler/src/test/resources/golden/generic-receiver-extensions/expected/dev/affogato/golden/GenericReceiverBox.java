package dev.affogato.golden;

public class GenericReceiverBox<T> {
    private T value;

    public GenericReceiverBox(T value) {
        this.value = value;
    }

    public T getValue() {
        return value;
    }

    public void setValue(T value) {
        this.value = value;
    }

}
