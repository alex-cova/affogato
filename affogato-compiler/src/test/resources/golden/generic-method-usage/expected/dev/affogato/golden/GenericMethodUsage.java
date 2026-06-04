package dev.affogato.golden;

public class GenericMethodUsage {
    public String run() {
        final GenericMethods methods = new GenericMethods();
        final Integer number = methods.identity(7);
        System.out.println(number);
        final String chosen = methods.choose("affogato", "latte");
        return chosen;
    }

}
