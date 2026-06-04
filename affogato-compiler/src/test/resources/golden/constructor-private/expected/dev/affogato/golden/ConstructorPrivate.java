package dev.affogato.golden;

public class ConstructorPrivate {
    public String run() {
        final PrivateInitToken token = PrivateInitToken.create("secret");
        return token.getValue();
    }

}
