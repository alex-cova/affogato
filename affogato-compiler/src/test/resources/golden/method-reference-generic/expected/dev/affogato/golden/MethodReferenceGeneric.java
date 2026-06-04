package dev.affogato.golden;

import java.util.function.Function;

public class MethodReferenceGeneric {
    public String run() {
        final Function<String,String> stringIdentity = GenericMethodReferences::identity;
        final Function<Integer,Integer> intIdentity = GenericMethodReferences::identity;
        final String text = stringIdentity.apply("affogato");
        final Integer number = intIdentity.apply(7);
        return text + ":" + number;
    }

}
