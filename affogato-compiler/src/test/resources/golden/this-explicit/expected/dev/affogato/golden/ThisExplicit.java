package dev.affogato.golden;

public class ThisExplicit {
    public int run() {
        final ThisCounter counter = new ThisCounter(1);
        return counter.increment();
    }

}
