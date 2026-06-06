package dev.affogato.golden;

public final class ExtensionChainingExtensions {
    public static String wrap(String $this) {
        return "[" + $this + "]";
    }

    public static String addBang(String $this) {
        return $this + "!";
    }

}
