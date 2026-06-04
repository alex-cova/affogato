package dev.affogato.golden;

public class NamedInitChoice {
    public final String label;
    public final int count;

    public NamedInitChoice(String label, int count) {
        this.label = label;
        this.count = count;
    }

    public NamedInitChoice(int count, String suffix) {
        this.label = suffix;
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
