package dev.affogato.golden;

public class OperatorPrecedenceComplex {
    public boolean check(int a, int b, int c, boolean flag) {
        return a + b * c > 20 && !(flag) || a - b / 2 == c % 3;
    }

    public int score(int a, int b, int c) {
        return (a + b) * (c - 1) / 2 + a % 3;
    }

}
