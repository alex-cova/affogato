package dev.affogato.golden;

public class OverloadUser {
    public String run() {
        final String first = LocalOverloads.route("a", 1);
        final String second = LocalOverloads.route(2L, "b");
        return first + second;
    }

}
