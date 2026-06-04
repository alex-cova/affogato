package dev.affogato.golden;

import java.util.List;

public class CastsGenerics {
    public String first(Object value) {
        final List<String> values = ((List<String>) value);
        return values.get(0);
    }

}
