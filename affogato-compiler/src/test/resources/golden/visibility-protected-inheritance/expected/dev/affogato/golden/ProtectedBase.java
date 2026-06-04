package dev.affogato.golden;

public class ProtectedBase {
    protected String prefix = "base";

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String value) {
        this.prefix = value;
    }

    protected String format(String value) {
        return prefix + ":" + value;
    }

}
