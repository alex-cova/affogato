package dev.affogato.golden;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;

public class JavaImports {
    public String run() {
        final java.time.LocalDate today = LocalDate.of(2026, 6, 4);
        ArrayList<String> names = new ArrayList<String>();
        names.add("affogato");
        HashMap<String,Integer> counts = new HashMap<String, Integer>();
        counts.put("shots", 2);
        System.out.println(names);
        System.out.println(counts);
        return today.toString();
    }

}
