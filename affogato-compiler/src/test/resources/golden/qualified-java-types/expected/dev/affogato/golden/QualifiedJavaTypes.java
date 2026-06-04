package dev.affogato.golden;

import java.time.LocalDate;
import java.util.ArrayList;

public class QualifiedJavaTypes {
    public String run() {
        final java.time.LocalDate date = LocalDate.of(2026, 6, 4);
        final ArrayList<String> list = new ArrayList<String>();
        list.add("affogato");
        System.out.println(list);
        return date.toString();
    }

}
