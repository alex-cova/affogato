package dev.affogato.golden;

public class OverloadsInheritanceSpecificity {
    public String run() {
        final SpecificChild child = new SpecificChild();
        final String specific = child.choose("affogato");
        final String inherited = child.choose(new StringBuilder("builder"));
        return specific + ":" + inherited;
    }

}
