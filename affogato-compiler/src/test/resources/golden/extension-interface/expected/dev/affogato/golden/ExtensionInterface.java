package dev.affogato.golden;

public class ExtensionInterface {
    public String run() {
        final NamedCup cup = new NamedCup();
        return dev.affogato.golden.ExtensionInterfaceExtensions.display(cup);
    }

}
