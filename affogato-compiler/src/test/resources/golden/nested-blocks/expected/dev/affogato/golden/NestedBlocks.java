package dev.affogato.golden;

public class NestedBlocks {
    public String run(int value) {
        {
            if (value > 0) {
                return "positive";
            }
        }
        return "other";
    }

}
