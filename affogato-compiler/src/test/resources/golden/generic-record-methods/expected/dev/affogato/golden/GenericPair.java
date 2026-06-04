package dev.affogato.golden;

public record GenericPair<A, B>(A first, B second) {
    public A firstValue() {
        return first;
    }

    public B secondValue() {
        return second;
    }

}
