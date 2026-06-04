package dev.affogato.golden;

import dev.affogato.golden.interop.TagList;

@TagList(names = {"af", "fogato"}, levels = {1, 2})
public class ArrayAnnotatedType {
    public String label() {
        return "array";
    }

}
