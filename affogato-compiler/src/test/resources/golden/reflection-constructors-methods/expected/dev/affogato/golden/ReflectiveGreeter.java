package dev.affogato.golden;

public class ReflectiveGreeter {
    private String name;

    public ReflectiveGreeter(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String value) {
        this.name = value;
    }

    public String greet(String prefix) {
        return prefix + " " + name;
    }

}
