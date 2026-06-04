package dev.affogato.golden;

public class TryCatchFinally {
    public String parse(String value) {
        try {
            return Integer.parseInt(value) + "";
        } catch (NumberFormatException e) {
            return "nan";
        }
    }

    public String cleanup() {
        try {
            System.out.println("attempt");
        } catch (RuntimeException e) {
            System.out.println(e.getMessage());
        } finally {
            System.out.println("cleanup");
        }
        return "done";
    }

}
