package dev.affogato.golden;

import dev.affogato.golden.interop.JavaFieldBox;

public class JavaFieldsStaticInstance {
    public String run() {
        JavaFieldBox.staticLabel = "changed";
        final JavaFieldBox box = new JavaFieldBox(3);
        box.count = box.count + 4;
        final String label = JavaFieldBox.staticLabel;
        return label + ":" + box.count + ":" + box.finalLabel;
    }

}
