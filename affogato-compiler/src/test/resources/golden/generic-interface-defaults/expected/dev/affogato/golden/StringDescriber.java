package dev.affogato.golden;

public class StringDescriber implements Describer<String> {
    @Override
    public String describe(String value) {
        return "new String[]{" + value + "}";
    }

}
