package dev.affogato.golden;

import java.util.ArrayList;

public class CollectionsRemoveContains {
    public String run() {
        final ArrayList<String> values = new ArrayList<String>();
        values.add("af");
        values.add("fogato");
        final boolean before = values.contains("af");
        values.remove("af");
        final boolean after = values.contains("af");
        final boolean empty = values.isEmpty();
        return before + ":" + after + ":" + empty;
    }

}
