package dev.affogato.golden;

import java.util.List;

public record Page<T>(List<T> items) {
    public T first() {
        return items.get(0);
    }

    public int size() {
        return items.size();
    }

}
