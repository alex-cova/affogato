package dev.affogato.intellij.completion;

import com.intellij.codeInsight.completion.CompletionLocation;
import com.intellij.codeInsight.completion.CompletionWeigher;
import com.intellij.codeInsight.lookup.LookupElement;
import dev.affogato.intellij.completion.lookup.AffogatoWeightedLookupElement;
import org.jetbrains.annotations.NotNull;

public final class AffogatoCompletionWeigher extends CompletionWeigher {
    @Override
    public @NotNull Comparable<?> weigh(@NotNull LookupElement element, @NotNull CompletionLocation location) {
        return AffogatoWeightedLookupElement.weightOf(element);
    }
}
