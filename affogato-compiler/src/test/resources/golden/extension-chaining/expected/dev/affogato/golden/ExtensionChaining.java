package dev.affogato.golden;

public class ExtensionChaining {
    public String run() {
        return dev.affogato.golden.ExtensionChainingExtensions.addBang(dev.affogato.golden.ExtensionChainingExtensions.wrap("ok"));
    }

}
