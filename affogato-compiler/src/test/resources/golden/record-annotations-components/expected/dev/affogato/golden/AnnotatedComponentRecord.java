package dev.affogato.golden;

public record AnnotatedComponentRecord(@Deprecated String name, @SuppressWarnings("unused") int count) {
    public String label() {
        return name + count;
    }

}
