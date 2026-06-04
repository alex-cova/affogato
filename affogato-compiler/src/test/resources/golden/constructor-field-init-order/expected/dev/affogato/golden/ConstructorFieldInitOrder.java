package dev.affogato.golden;

public class ConstructorFieldInitOrder {
    public String run() {
        final OrderedInitBox box = new OrderedInitBox("ctor");
        return box.describe();
    }

}
