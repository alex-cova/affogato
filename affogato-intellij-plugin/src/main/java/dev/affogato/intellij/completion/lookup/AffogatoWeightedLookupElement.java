package dev.affogato.intellij.completion.lookup;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementDecorator;
import org.jetbrains.annotations.NotNull;

public final class AffogatoWeightedLookupElement extends LookupElementDecorator<LookupElement> {
    private final int weight;

    private AffogatoWeightedLookupElement(@NotNull LookupElement delegate, int weight) {
        super(delegate);
        this.weight = weight;
    }

    static @NotNull LookupElement wrap(@NotNull LookupElement delegate, int weight) {
        if (delegate instanceof AffogatoWeightedLookupElement existing) {
            return new AffogatoWeightedLookupElement(existing.getDelegate(), weight);
        }
        return new AffogatoWeightedLookupElement(delegate, weight);
    }

    int weight() {
        return weight;
    }

    public static int weightOf(@NotNull LookupElement element) {
        if (element instanceof AffogatoWeightedLookupElement weighted) {
            return weighted.weight();
        }
        return 0;
    }

    @Override
    public @NotNull String getLookupString() {
        return getDelegate().getLookupString();
    }

}
