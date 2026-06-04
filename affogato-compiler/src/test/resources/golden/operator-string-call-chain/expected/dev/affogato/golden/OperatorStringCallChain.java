package dev.affogato.golden;

public class OperatorStringCallChain {
    public String render(StringBuilder value) {
        return "value=" + (value.append("!").toString().toUpperCase());
    }

}
