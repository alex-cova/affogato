package dev.affogato.golden;

public class GenericStaticMethods {
    public String run() {
        final String id = GenericStatics.identity("espresso");
        final Integer chosen = GenericStatics.choose(1, 2);
        return id + chosen;
    }

}
