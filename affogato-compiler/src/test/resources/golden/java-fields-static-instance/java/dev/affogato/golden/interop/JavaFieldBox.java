package dev.affogato.golden.interop;

public final class JavaFieldBox {
    public static String staticLabel = "initial";
    public int count;
    public final String finalLabel = "final";

    public JavaFieldBox(int count) {
        this.count = count;
    }
}
