package dev.affogato.golden;

public class AnnotationsMethodParams {
    public String combine(@Deprecated String left, @SuppressWarnings("unused") String right) {
        return left + right;
    }

    public String run() {
        return combine("af", "fogato");
    }

}
