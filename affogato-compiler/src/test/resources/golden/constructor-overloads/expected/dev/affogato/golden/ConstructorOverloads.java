package dev.affogato.golden;

public class ConstructorOverloads {
    public String run() {
        final OverloadedInitBox defaulted = new OverloadedInitBox("plain");
        final OverloadedInitBox counted = new OverloadedInitBox("counted", 3);
        return defaulted.describe() + "|" + (counted.describe());
    }

}
