package dev.affogato.golden;

public class ObjectArrays {
    public String run() {
        final Object[] items = new Object[]{new ArrayItem("a"), new ArrayItem("b")};
        String text = "";
        for (var item : items) {
            text = text + item.toString();
        }
        return text + items.length;
    }

}
