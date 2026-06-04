package dev.affogato.golden;

public class BlockThisCounter {
    private int value;

    public BlockThisCounter(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }

    public int update(int value) {
        {
            final int local = value + 1;
            this.value = this.value + local;
        }
        return this.value;
    }

}
