package dev.affogato.golden;

public class SpecificChild extends SpecificBase {
    @Override
    public String choose(CharSequence value) {
        return "child-char:" + value.toString();
    }

    public String choose(String value) {
        return "child-string:" + value;
    }

}
