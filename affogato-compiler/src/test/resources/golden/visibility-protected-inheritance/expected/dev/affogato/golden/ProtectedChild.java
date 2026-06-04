package dev.affogato.golden;

public class ProtectedChild extends ProtectedBase {
    public String run(String value) {
        this.prefix = "child";
        return format(value);
    }

}
