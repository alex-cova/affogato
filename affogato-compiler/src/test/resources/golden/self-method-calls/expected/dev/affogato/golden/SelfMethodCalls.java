package dev.affogato.golden;

public class SelfMethodCalls {
    public String greet(String name) {
        return "Hello " + name;
    }

    public String excited(String name) {
        return this.greet(name) + "!";
    }

    public String run() {
        return excited("Ada");
    }

}
