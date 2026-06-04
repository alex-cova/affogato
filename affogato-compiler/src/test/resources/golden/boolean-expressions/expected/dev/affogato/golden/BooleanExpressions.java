package dev.affogato.golden;

public class BooleanExpressions {
    public String check(String name, int count) {
        if (!(name.isBlank()) && count > 0) {
            return "ready";
        }
        return count == 0 ? "empty" : "blocked";
    }

}
