package dev.affogato.golden;

import dev.affogato.golden.interop.CollectionWildcardFixtures;

public class CollectionsWildcardValues {
    public String run() {
        final java.util.Map<java.lang.String, ? extends java.lang.Number> numbers = CollectionWildcardFixtures.numberMap();
        final java.lang.Number value = CollectionWildcardFixtures.firstNumber(numbers);
        System.out.println(value);
        final boolean empty = numbers.isEmpty();
        return empty ? "empty" : "present";
    }

}
