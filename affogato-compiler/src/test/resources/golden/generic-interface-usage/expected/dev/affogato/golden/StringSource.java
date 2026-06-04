package dev.affogato.golden;

public class StringSource implements Source<String> {
    @Override
    public String get() {
        return "value";
    }

}
