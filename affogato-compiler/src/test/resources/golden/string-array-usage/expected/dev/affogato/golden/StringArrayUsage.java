package dev.affogato.golden;

public class StringArrayUsage {
    public String join() {
        final String[] labels = new String[]{"a", "b", "c"};
        String text = "";
        for (var label : labels) {
            text = text + label;
        }
        return text + labels.length;
    }

}
