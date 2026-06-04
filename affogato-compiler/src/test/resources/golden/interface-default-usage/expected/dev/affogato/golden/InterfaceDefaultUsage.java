package dev.affogato.golden;

public class InterfaceDefaultUsage {
    public String run() {
        final NamedWidget widget = new NamedWidget();
        return widget.label();
    }

}
