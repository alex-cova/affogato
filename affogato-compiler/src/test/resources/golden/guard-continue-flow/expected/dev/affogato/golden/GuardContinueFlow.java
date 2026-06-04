package dev.affogato.golden;

import java.util.List;

public class GuardContinueFlow {
    public String firstString(List<Object> values) {
        for (var value : values) {
            if (!(value instanceof String)) {
                continue;
            }
            return ((String) value);
        }
        return "none";
    }

}
