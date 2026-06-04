package dev.affogato.golden;

public class GenericInterfaceDefaults {
    public String run() {
        final Describer<String> describer = new StringDescriber();
        return describer.describeTwice("mocha");
    }

}
