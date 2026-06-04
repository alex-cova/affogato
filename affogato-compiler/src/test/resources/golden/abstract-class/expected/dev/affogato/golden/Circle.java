package dev.affogato.golden;

public class Circle extends Shape {
    public final double radius;

    public Circle(double radius) {
        this.radius = radius;
    }

    public double getRadius() {
        return radius;
    }

    public double area() {
        return 3.14 * radius * radius;
    }

}
