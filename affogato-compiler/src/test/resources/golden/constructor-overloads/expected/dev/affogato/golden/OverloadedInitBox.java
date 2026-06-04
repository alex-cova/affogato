package dev.affogato.golden;

public class OverloadedInitBox {
    public final String label;
    public final int count;

    public OverloadedInitBox(String label) {
        this.label = label;
        this.count = 0;
    }

    public OverloadedInitBox(String label, int count) {
        this.label = label;
        this.count = count;
    }

    public String getLabel() {
        return label;
    }

    public int getCount() {
        return count;
    }

    public String describe() {
        return label + ":" + count;
    }

}
