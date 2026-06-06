package dev.affogato.golden;

import java.util.function.Function;

public class LambdaBlockBody {
    public String run() {
        final Function<String,String> mapper = value -> {
    String suffix = "!";
    return value + suffix;
};
        return mapper.apply("af");
    }

}
