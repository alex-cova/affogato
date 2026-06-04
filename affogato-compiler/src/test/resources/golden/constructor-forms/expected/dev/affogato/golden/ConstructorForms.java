package dev.affogato.golden;

public class ConstructorForms {
    public int run() {
        final PairPoint shorthand = new PairPoint(1, 2);
        final PairPoint explicit = new PairPoint(3, 4);
        final PairPoint named = new PairPoint(5, 6);
        return shorthand.sum() + explicit.sum() + named.sum();
    }

}
