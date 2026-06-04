package dev.affogato.golden;

public class GenericsBounds {
    public Integer run() {
        final NumberBox<Integer> box = new NumberBox<Integer>(7);
        return box.get();
    }

}
