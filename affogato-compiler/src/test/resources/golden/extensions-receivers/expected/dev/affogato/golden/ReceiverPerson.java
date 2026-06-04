package dev.affogato.golden;

public class ReceiverPerson {
    private String name;
    private final int age;

    public ReceiverPerson(String name, int age) {
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
