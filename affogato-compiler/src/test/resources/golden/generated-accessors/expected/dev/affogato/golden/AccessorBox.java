package dev.affogato.golden;

public class AccessorBox {
    private String value;

    public AccessorBox(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

}
