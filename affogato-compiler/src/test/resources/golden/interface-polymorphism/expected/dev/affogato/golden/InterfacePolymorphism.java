package dev.affogato.golden;

public class InterfacePolymorphism {
    public String run(boolean flag) {
        final Formatter formatter = flag ? new UpperFormatter() : new PrefixFormatter();
        return formatter.format("ok");
    }

}
