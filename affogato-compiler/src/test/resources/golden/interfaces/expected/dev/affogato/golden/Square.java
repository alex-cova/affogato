package dev.affogato.golden;

public class Square implements Drawable {
    public final int side;

    public Square(int side) {
        this.side = side;
    }

    public int getSide() {
        return side;
    }

    @Override
    public void draw() {
        System.out.println("square");
    }

    @Override
    public String describe() {
        return "Square";
    }

}
