package dev.affogato.golden;

public record Point2D(int x, int y) {
    public int sum() {
        return x + y;
    }

}
