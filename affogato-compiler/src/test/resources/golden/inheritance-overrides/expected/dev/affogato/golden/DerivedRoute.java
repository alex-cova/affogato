package dev.affogato.golden;

public class DerivedRoute extends BaseRoute {
    @Override
    public String label(String value) {
        return "derived" + value;
    }

}
