package dev.affogato.golden;

public class RecordMethodOverload {
    public String run() {
        final RecordFormatter formatter = new RecordFormatter("item");
        final String text = formatter.format("af");
        final String number = formatter.format(2);
        return text + ":" + number;
    }

}
