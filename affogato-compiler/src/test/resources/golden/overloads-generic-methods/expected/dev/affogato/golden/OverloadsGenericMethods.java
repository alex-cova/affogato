package dev.affogato.golden;

import java.util.List;

public class OverloadsGenericMethods {
    public String run() {
        final String fromString = GenericOverloadMethods.pick("affogato");
        final String fromList = GenericOverloadMethods.pick(List.of("af", "fogato"));
        final String fromGeneric = GenericOverloadMethods.pick(Integer.valueOf(3));
        return fromString + ":" + fromList + ":" + fromGeneric;
    }

}
