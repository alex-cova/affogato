package dev.affogato.golden;

import dev.affogato.runtime.NotNull;
import java.util.Objects;

public class Person {
    private @NotNull String name;
    private final int age;

    public Person(@NotNull String name, int age) {
        Objects.requireNonNull(name, "name");
        this.name = name;
        this.age = age;
    }

    public @NotNull String getName() {
        return name;
    }

    public void setName(@NotNull String value) {
        Objects.requireNonNull(value, "value");
        this.name = value;
    }

    public int getAge() {
        return age;
    }

    @Override
    public String toString() {
        return name;
    }

}
