package dev.affogato.golden;

import java.util.function.Supplier;

public class Panel implements Component {
    public Panel(Supplier<java.util.List<Component>> supplier) {
        System.out.println(supplier.get());
    }

    @Override
    public String render() {
        return "panel";
    }

}
