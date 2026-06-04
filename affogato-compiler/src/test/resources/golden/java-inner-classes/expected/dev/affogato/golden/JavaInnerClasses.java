package dev.affogato.golden;

import dev.affogato.golden.interop.JavaOuter;
import java.util.HashMap;
import java.util.Map;

public class JavaInnerClasses {
    public String run() {
        final HashMap<String,Integer> map = new HashMap<String, Integer>();
        map.put("shots", 2);
        final Map.Entry<String,Integer> entry = map.entrySet().iterator().next();
        final JavaOuter.Inner inner = new JavaOuter.Inner("inside");
        final String key = entry.getKey();
        final Integer value = entry.getValue();
        final java.lang.String label = inner.label();
        return key + ":" + value + ":" + label;
    }

}
