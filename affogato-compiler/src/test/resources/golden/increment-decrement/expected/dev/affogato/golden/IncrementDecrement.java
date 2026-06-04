package dev.affogato.golden;

public class IncrementDecrement {
    public int run() {
        int i = 0;
        i++;
        i++;
        i--;
        ++i;
        --i;
        return i;
    }

    public int modulo(int x) {
        int r = x;
        r %= 3;
        return r;
    }

}
