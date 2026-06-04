package dev.affogato.golden;

import java.util.function.Supplier;

public class Button implements Component {
    public final String text;
    public final Runnable action;

    public Button(String text, Runnable action) {
        this.text = text;
        this.action = action;
    }

    public String getText() {
        return text;
    }

    public Runnable getAction() {
        return action;
    }

    @Override
    public String render() {
        return text;
    }

}
