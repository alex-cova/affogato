package dev.affogato.golden.interop;

public interface JavaNamed {
    String name();

    default String label() {
        return "named:" + name();
    }
}
