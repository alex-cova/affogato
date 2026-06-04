package dev.affogato.golden;

public class PairPoint {
    public final int x;
    public final int y;

    public PairPoint(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int sum() {
        return x + y;
    }

}
