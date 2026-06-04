package dev.affogato.golden;

public class FieldInitializers {
    public int count = 1;
    public String label = "ready";

    public int getCount() {
        return count;
    }

    public void setCount(int value) {
        this.count = value;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String value) {
        this.label = value;
    }

    public String run() {
        count = count + 1;
        return label + count;
    }

}
