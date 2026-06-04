package dev.affogato.golden;

public interface Drawable {
    void draw();
    String describe();
    default void label() {
        System.out.println("Drawable");
    }

}
