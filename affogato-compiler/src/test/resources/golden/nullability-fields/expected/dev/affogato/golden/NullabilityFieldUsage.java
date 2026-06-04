package dev.affogato.golden;

public class NullabilityFieldUsage {
    public String run() {
        final NullabilityFields holder = new NullabilityFields(" ready ", null);
        return holder.describe();
    }

}
