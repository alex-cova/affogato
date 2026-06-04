package dev.affogato.golden;

public class StaticMethodOverload {
    public String run() {
        final String text = StaticOverloadSource.pick("af");
        final String number = StaticOverloadSource.pick(2);
        return text + "|" + number;
    }

}
