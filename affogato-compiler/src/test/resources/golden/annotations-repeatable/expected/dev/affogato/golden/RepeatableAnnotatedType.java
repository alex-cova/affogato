package dev.affogato.golden;

import dev.affogato.golden.interop.Role;

@Role("reader")
@Role("writer")
public class RepeatableAnnotatedType {
    public String label() {
        return "repeatable";
    }

}
