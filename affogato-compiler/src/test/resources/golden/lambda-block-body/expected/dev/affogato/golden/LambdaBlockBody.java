package dev.affogato.golden;

import java.util.function.Function;

public class LambdaBlockBody {
    public String run() {
        final Function<String,String> mapper = value -> {
            var suffix = "!";
            return value + suffix;
        };
        return mapper.apply("af");
    }

}
