package dev.affogato.golden;

import java.util.List;

public class InstanceofGenerics {
    public String describe(Object value) {
        if (value instanceof List) {
            final List<String> values = ((List<String>) value);
            return "list:" + (values.size());
        }
        if (value instanceof List) {
            return "raw";
        }
        return "other";
    }

}
