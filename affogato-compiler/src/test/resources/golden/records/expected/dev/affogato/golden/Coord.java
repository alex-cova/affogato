package dev.affogato.golden;

@SuppressWarnings("unused")
public record Coord(int x, int y) {
    public int sum() {
        return x + y;
    }

}
