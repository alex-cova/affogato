package dev.affogato.golden;

public class TryHelperCall {
    public int parse(String value) {
        return Integer.parseInt(value);
    }

    public String run(String value) {
        try {
            final int parsed = parse(value);
            return "ok" + parsed;
        } catch (NumberFormatException e) {
            return "bad";
        }
    }

}
