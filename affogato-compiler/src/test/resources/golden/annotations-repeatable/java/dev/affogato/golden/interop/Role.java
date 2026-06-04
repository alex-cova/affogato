package dev.affogato.golden.interop;

import java.lang.annotation.Repeatable;

@Repeatable(Roles.class)
public @interface Role {
    String value();
}
