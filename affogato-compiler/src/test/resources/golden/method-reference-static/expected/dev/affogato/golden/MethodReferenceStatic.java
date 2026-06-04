package dev.affogato.golden;

import java.util.function.Function;

public class MethodReferenceStatic {
    public Integer parse(Function<String,Integer> fn) {
        return fn.apply("42");
    }

    public Integer run() {
        return parse(Integer::parseInt);
    }

}
