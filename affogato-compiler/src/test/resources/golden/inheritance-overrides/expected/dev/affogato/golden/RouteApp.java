package dev.affogato.golden;

public class RouteApp {
    public String run() {
        final DerivedRoute route = new DerivedRoute();
        return route.label("x");
    }

}
