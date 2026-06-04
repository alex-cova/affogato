package dev.affogato.golden;

public class VisibilityPrivateMethod {
    public String run(String value) {
        return normalize(value);
    }

    private String normalize(String value) {
        return value.trim().toUpperCase();
    }

}
