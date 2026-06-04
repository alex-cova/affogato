package dev.affogato.golden;

public class ListTypeSugar {
    public java.util.List<String> run() {
        java.util.ArrayList<String> names = new java.util.ArrayList<String>();
        names.add("espresso");
        names.add("latte");
        return names;
    }

}
