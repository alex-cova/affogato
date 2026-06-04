package dev.affogato.golden;

import java.lang.reflect.Array;

public class ReflectionArrays {
    public String run() {
        final java.lang.Object values = Array.newInstance(String.class, 2);
        Array.set(values, 0, "af");
        Array.set(values, 1, "fogato");
        final java.lang.String first = Array.get(values, 0).toString();
        final java.lang.String second = Array.get(values, 1).toString();
        final int size = Array.getLength(values);
        return first + second + ":" + size;
    }

}
