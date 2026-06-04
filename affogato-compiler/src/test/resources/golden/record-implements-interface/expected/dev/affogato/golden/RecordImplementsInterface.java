package dev.affogato.golden;

public class RecordImplementsInterface {
    public String run() {
        final RecordValue<String> value = new RecordBox<String>("affogato");
        return value.value();
    }

}
