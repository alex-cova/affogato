package dev.affogato.golden;

public record RecordBox<T>(T value) implements RecordValue<T> {
    public String describe() {
        return value.toString();
    }

}
