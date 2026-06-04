package dev.affogato.golden;

public class ConstructorNamedArgsOverload {
    public String run() {
        final NamedInitChoice first = new NamedInitChoice("name", 1);
        final NamedInitChoice second = new NamedInitChoice(2, "suffix");
        return first.describe() + "|" + (second.describe());
    }

}
