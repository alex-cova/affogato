package dev.affogato.golden;

public class NamedPerson {
    private String name;
    private final int age;

    public NamedPerson(String name, int age) {
        this.name = name;
        this.age = age;
    }

    public String getName() {
        return name;
    }

    public void setName(String value) {
        this.name = value;
    }

    public int getAge() {
        return age;
    }

}
