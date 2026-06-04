package dev.affogato.golden;

import java.util.function.Function;

public class LambdaLocals {
    public String run() {
        final Function<String,String> mapper = value -> value + "!";
        final Function<String,String> trimmer = String::trim;
        return mapper.apply(trimmer.apply(" ok "));
    }

}
