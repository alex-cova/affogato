package dev.affogato.exec;

public final class Err {
    private Err() {
    }

    public static void println(String message) {
        System.err.println(message);
    }
}
