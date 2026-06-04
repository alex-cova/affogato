package dev.affogato.golden;

public class PrefixFormatter implements Formatter {
    @Override
    public String format(String value) {
        return "x-" + value;
    }

}
