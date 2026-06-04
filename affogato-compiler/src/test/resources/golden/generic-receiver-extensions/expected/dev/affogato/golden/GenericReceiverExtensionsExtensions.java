package dev.affogato.golden;

public final class GenericReceiverExtensionsExtensions {
    public static String label(GenericReceiverBox<String> $this, String prefix) {
        return prefix + ":" + $this.getValue();
    }

}
