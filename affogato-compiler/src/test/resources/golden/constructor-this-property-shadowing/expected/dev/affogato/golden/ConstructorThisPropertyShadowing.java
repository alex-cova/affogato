package dev.affogato.golden;

public class ConstructorThisPropertyShadowing {
    public String run() {
        final ShadowedInitBox box = new ShadowedInitBox("ctor", 2);
        return box.rename("method");
    }

}
