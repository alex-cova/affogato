package dev.affogato.golden;

public class InheritedOverloads {
    public String run() {
        final DerivedChoices choices = new DerivedChoices();
        return choices.pick("child");
    }

}
