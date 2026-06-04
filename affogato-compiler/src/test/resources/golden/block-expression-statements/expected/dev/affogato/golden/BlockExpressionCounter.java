package dev.affogato.golden;

public class BlockExpressionCounter {
    private int count;

    public BlockExpressionCounter(int count) {
        this.count = count;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int value) {
        this.count = value;
    }

    public int inc() {
        count = count + 1;
        return count;
    }

}
