package dev.affogato.golden;

public class BlockThisResolution {
    public int run() {
        final BlockThisCounter counter = new BlockThisCounter(10);
        return counter.update(5);
    }

}
