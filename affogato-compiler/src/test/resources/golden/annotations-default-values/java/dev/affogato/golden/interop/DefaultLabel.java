package dev.affogato.golden.interop;

public @interface DefaultLabel {
    String value() default "default";
    int priority() default 1;
}
