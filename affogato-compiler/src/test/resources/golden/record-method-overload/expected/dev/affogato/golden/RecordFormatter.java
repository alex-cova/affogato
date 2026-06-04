package dev.affogato.golden;

public record RecordFormatter(String prefix) {
    public String format(String value) {
        return prefix + ":" + value;
    }

    public String format(int value) {
        return prefix + ":" + value;
    }

}
