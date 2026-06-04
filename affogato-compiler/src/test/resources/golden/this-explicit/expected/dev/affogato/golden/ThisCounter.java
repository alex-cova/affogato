package dev.affogato.golden;

public class ThisCounter {
    private int count;

    public ThisCounter(int count) {
        this.count = count;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int value) {
        this.count = value;
    }

    public int increment() {
        this.count = this.count + 1;
        return this.count;
    }

}
