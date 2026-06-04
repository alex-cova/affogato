package dev.affogato.golden;

import java.util.function.Supplier;

public class Label implements Component {
    public final String text;

    public Label(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }

    @Override
    public String render() {
        return text;
    }

}
