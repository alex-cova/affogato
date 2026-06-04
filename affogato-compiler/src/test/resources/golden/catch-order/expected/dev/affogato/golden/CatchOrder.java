package dev.affogato.golden;

public class CatchOrder {
    public String recover(String value) {
        try {
            if (value.isBlank()) {
                throw new IllegalArgumentException("blank");
            }
            return value;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } catch (RuntimeException e) {
            return "runtime";
        }
    }

}
