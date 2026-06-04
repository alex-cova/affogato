package dev.affogato.golden;

public class RecordAnnotationsComponents {
    public String run() {
        final AnnotatedComponentRecord item = new AnnotatedComponentRecord("shots", 2);
        return item.label();
    }

}
