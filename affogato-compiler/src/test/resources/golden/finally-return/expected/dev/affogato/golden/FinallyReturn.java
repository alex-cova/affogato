package dev.affogato.golden;

public class FinallyReturn {
    public String done() {
        try {
            System.out.println("before");
        } finally {
            return "done";
        }
    }

}
