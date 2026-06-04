package dev.affogato.golden;

public class CrossFileApp {
    public String run() {
        final CrossUser user = new CrossUser("Ada", 42);
        return dev.affogato.golden.UserExtensionsExtensions.label(user, "user");
    }

}
