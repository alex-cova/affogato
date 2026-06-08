package com.alexcova.teenyjson.api;

/**
 * A simple interface for serializing a value into a String.
 */
public interface ValueSerializer<T> {
    String serialize(T value);

}
