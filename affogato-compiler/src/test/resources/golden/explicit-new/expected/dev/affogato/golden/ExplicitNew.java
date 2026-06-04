package dev.affogato.golden;

public class ExplicitNew {
    public String run() {
        final ExplicitThing thing = new ExplicitThing("box");
        return thing.getName();
    }

}
