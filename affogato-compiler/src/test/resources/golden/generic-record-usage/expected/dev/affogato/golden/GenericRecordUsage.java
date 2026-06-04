package dev.affogato.golden;

public class GenericRecordUsage {
    public String run() {
        final Entry<String,Integer> entry = new Entry<String, Integer>("shots", 2);
        return entry.key() + "" + entry.value();
    }

}
