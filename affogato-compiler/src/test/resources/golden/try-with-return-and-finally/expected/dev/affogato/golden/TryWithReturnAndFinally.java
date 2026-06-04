package dev.affogato.golden;

public class TryWithReturnAndFinally {
    public String run(String value) {
        String marker = "start";
        try {
            if (value.isBlank()) {
                return "blank";
            }
            return value;
        } finally {
            marker = "finished";
            System.out.println(marker);
        }
    }

}
