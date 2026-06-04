package dev.affogato.golden;

public abstract class Shape {
    public abstract double area();

    public String describe() {
        return "Shape with area " + area();
    }

}
