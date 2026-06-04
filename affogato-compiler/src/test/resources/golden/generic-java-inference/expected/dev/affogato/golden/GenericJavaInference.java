package dev.affogato.golden;

import java.util.Collections;
import java.util.Optional;

public class GenericJavaInference {
    public java.util.List<String> singleton() {
        return Collections.singletonList("solo");
    }

    public String optional() {
        final java.util.Optional<String> value = Optional.of("present");
        return value.get();
    }

}
