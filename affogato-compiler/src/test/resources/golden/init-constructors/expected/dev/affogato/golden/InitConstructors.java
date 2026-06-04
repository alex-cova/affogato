package dev.affogato.golden;

public class InitConstructors {
    public String run() {
        final InitOnly item = new InitOnly("box", 2);
        final int next = item.bump();
        return item.getName() + next;
    }

}
