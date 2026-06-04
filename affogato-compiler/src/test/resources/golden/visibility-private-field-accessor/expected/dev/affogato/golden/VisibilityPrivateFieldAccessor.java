package dev.affogato.golden;

public class VisibilityPrivateFieldAccessor {
    public String run() {
        final PrivateFieldBox box = new PrivateFieldBox();
        box.setSecret("changed");
        final String current = box.getSecret();
        final String revealed = box.reveal();
        return current + ":" + revealed;
    }

}
