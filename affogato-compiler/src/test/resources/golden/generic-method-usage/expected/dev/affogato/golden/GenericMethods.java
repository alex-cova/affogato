package dev.affogato.golden;

public class GenericMethods {
    public <T> T identity(T value) {
        return value;
    }

    public <T> T choose(T first, T second) {
        return first;
    }

}
