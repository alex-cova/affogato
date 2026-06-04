package dev.affogato.golden;

public class UpperFormatter implements Formatter {
    @Override
    public String format(String value) {
        return value.toUpperCase();
    }

}
