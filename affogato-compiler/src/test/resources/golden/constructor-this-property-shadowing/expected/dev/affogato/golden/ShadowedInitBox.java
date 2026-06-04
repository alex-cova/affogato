package dev.affogato.golden;

public class ShadowedInitBox {
    public String name = "field";
    public int count = 0;

    public ShadowedInitBox(String name, int count) {
        this.name = name;
        this.count = count;
    }

    public String getName() {
        return name;
    }

    public void setName(String value) {
        this.name = value;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int value) {
        this.count = value;
    }

    public String rename(String name) {
        final String before = this.name;
        this.name = name;
        return before + ":" + this.name + ":" + name;
    }

}
