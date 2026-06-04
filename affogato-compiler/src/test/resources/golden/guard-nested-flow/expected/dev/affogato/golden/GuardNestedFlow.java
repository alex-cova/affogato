package dev.affogato.golden;

import java.util.List;

public class GuardNestedFlow {
    public String inspect(List<Object> values) {
        if (values.size() > 0) {
            for (var value : values) {
                if (!(value instanceof String)) {
                    continue;
                }
                return ((String) value);
            }
        }
        return "none";
    }

}
