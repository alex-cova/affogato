package dev.affogato.golden;

public class Throwing {
    public String fail(String message) {
        throw new RuntimeException(message);
    }

    public String recover() {
        try {
            return fail("bad");
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

}
