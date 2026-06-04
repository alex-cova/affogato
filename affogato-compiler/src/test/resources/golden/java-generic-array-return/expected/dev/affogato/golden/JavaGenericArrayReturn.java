package dev.affogato.golden;

import dev.affogato.golden.interop.JavaGenericArrays;

public class JavaGenericArrayReturn {
    public String run() {
        final String[] values = JavaGenericArrays.arrayOf("af", "fogato");
        String text = "";
        for (var value : values) {
            text = text + value;
        }
        return text + ":" + values.length;
    }

}
