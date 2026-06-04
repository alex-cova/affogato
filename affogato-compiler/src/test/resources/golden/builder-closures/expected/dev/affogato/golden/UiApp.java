package dev.affogato.golden;

import java.util.function.Supplier;

public class UiApp {
    public String build() {
        final Panel panel = new Panel(() -> {
java.util.List<Component> $children = new java.util.ArrayList<>();
$children.add(new Label("Hello"));
$children.add(new Button("Run", () -> {}));
return $children;
});
        return panel.render();
    }

}
