package dev.affogato.golden;

import java.util.function.Function;
import java.util.function.Supplier;

public class TrailingClosures {
    public String run() {
        final String mapped = TrailingClosures.map("seed", item -> item + "!");
        final String supplied = TrailingClosures.supply(() -> "ready");
        final String shouted = TrailingClosures.shout("hi");
        return mapped + supplied + shouted;
    }

    public static String map(String value, Function<String,String> mapper) {
        return mapper.apply(value);
    }

    public static String supply(Supplier<String> factory) {
        return factory.get();
    }

    public static String shout(String word) {
        return TrailingClosures.map(word, item -> item + "???");
    }

}
