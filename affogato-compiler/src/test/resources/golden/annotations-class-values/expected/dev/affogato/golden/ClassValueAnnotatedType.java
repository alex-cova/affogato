package dev.affogato.golden;

import dev.affogato.golden.interop.TargetType;

@TargetType(String.class)
public class ClassValueAnnotatedType {
    public String label() {
        return "class";
    }

}
