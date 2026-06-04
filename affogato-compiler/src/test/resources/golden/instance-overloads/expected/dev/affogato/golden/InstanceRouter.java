package dev.affogato.golden;

public class InstanceRouter {
    public String route(String value) {
        return value;
    }

    public String route(int value) {
        return "count" + value;
    }

}
