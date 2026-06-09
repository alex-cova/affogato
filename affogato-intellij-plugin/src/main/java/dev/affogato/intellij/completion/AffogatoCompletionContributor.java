package dev.affogato.intellij.completion;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.patterns.PlatformPatterns;
import dev.affogato.intellij.AffogatoLanguage;

public final class AffogatoCompletionContributor extends CompletionContributor {
    public AffogatoCompletionContributor() {
        extend(
                CompletionType.BASIC,
                PlatformPatterns.psiElement().withLanguage(AffogatoLanguage.INSTANCE),
                new AffogatoKeywordCompletionProvider()
        );
        extend(
                CompletionType.BASIC,
                PlatformPatterns.psiElement().withLanguage(AffogatoLanguage.INSTANCE),
                new AffogatoSymbolCompletionProvider()
        );
        extend(
                CompletionType.BASIC,
                PlatformPatterns.psiElement().withLanguage(AffogatoLanguage.INSTANCE),
                new AffogatoMemberCompletionProvider()
        );
        extend(
                CompletionType.BASIC,
                PlatformPatterns.psiElement().withLanguage(AffogatoLanguage.INSTANCE),
                new AffogatoImportCompletionProvider()
        );
    }
}
