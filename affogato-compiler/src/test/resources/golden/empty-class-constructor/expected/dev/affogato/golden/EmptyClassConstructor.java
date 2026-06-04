package dev.affogato.golden;

public class EmptyClassConstructor {
    public String run() {
        final EmptyThing thing = new EmptyThing();
        System.out.println(thing);
        return "ok";
    }

}
