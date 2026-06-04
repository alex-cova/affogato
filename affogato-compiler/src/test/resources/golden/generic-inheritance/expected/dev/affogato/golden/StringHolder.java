package dev.affogato.golden;

public class StringHolder extends Holder<String> {
    @Override
    public String echo(String value) {
        return "string:" + value;
    }

}
