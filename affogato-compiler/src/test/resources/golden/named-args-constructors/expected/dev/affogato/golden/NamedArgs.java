package dev.affogato.golden;

public class NamedArgs {
    public String build() {
        final NamedPerson person = new NamedPerson("Ada", 42);
        final String label = NamedArgs.join("A", "B");
        return person.getName() + person.getAge() + label;
    }

    public static String join(String first, String second) {
        return first + second;
    }

}
