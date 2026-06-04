package dev.affogato.golden;

public class MultiCatchSubtyping {
    public String recover(int kind) {
        try {
            if (kind == 1) {
                throw new IllegalArgumentException("bad");
            }
            if (kind == 2) {
                throw new IllegalStateException("state");
            }
            if (kind == 3) {
                throw new RuntimeException("runtime");
            }
            return "ok";
        } catch (IllegalArgumentException | IllegalStateException e) {
            return e.getMessage();
        } catch (RuntimeException e) {
            return "runtime";
        }
    }

}
