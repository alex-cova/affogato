package dev.affogato.golden;

public class InitOnly {
    public final String name;
    public int count;

    public InitOnly(String name, int count) {
        this.name = name;
        this.count = count;
    }

    public String getName() {
        return name;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int value) {
        this.count = value;
    }

    public int bump() {
        count = count + 1;
        return count;
    }

}
