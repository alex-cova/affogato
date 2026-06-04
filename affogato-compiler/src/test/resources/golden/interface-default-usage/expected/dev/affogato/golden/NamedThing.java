package dev.affogato.golden;

public interface NamedThing {
    String name();
    default String label() {
        return "thing:" + name();
    }

}
