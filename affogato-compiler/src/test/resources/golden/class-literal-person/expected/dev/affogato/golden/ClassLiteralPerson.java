package dev.affogato.golden;

public class ClassLiteralPerson {
    public String run() {
        final java.lang.Class personType = Person.class;
        final java.lang.Class elementType = Person[].class;
        return personType.getName() + ":" + personType.getSimpleName() + ":" + elementType.getSimpleName();
    }

}
