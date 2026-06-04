package dev.affogato.golden;

public class GenericReceiverExtensions {
    public String run() {
        final GenericReceiverBox<String> stringBox = new GenericReceiverBox<String>("bean");
        return dev.affogato.golden.GenericReceiverExtensionsExtensions.label(stringBox, "kind");
    }

}
