package dev.affogato.golden;

public interface Describer<T> {
    String describe(T value);
    default String describeTwice(T value) {
        return describe(value) + describe(value);
    }

}
