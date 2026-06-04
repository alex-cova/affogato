package dev.affogato.golden;

import java.util.function.Function;

public class LambdaCapturesLetLocal {
    public String run() {
        final String prefix = "af";
        final Function<String,String> mapper = value -> prefix + value;
        return mapper.apply("fogato");
    }

}
