package dev.affogato.golden;

public class VisibilityProtectedInheritance {
    public String run() {
        final ProtectedChild child = new ProtectedChild();
        return child.run("visible");
    }

}
