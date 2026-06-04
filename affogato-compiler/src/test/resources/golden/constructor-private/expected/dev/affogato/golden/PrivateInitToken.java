package dev.affogato.golden;

public class PrivateInitToken {
    public final String value;

    private PrivateInitToken(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static PrivateInitToken create(String value) {
        return new PrivateInitToken(value);
    }

}
