package dev.affogato.golden;

public class OrderedInitBox {
    public String label = "field";
    public int count = 1;

    public OrderedInitBox(String label) {
        this.count = this.count + 1;
        this.label = label + ":" + this.count;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String value) {
        this.label = value;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int value) {
        this.count = value;
    }

    public String describe() {
        return label + ":" + count;
    }

}
