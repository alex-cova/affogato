package dev.affogato.intellij.findusages;

import com.intellij.lang.cacheBuilder.DefaultWordsScanner;
import com.intellij.lang.cacheBuilder.WordsScanner;
import com.intellij.lang.findUsages.FindUsagesProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.TokenSet;
import dev.affogato.intellij.lexer.AffogatoLexerAdapter;
import dev.affogato.intellij.psi.AffogatoPsiUtil;
import dev.affogato.intellij.psi.AffogatoTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class AffogatoFindUsagesProvider implements FindUsagesProvider {
    private static final WordsScanner WORDS_SCANNER = new DefaultWordsScanner(
            new AffogatoLexerAdapter(),
            TokenSet.create(AffogatoTypes.ID),
            TokenSet.create(AffogatoTypes.LINE_COMMENT),
            TokenSet.create(AffogatoTypes.STRING)
    );

    @Override
    public @Nullable WordsScanner getWordsScanner() {
        return WORDS_SCANNER;
    }

    @Override
    public boolean canFindUsagesFor(@NotNull PsiElement psiElement) {
        return AffogatoPsiUtil.isDeclarationIdentifier(psiElement);
    }

    @Override
    public @Nullable String getHelpId(@NotNull PsiElement psiElement) {
        return null;
    }

    @Override
    public @NotNull String getType(@NotNull PsiElement element) {
        return switch (AffogatoPsiUtil.declarationKind(element)) {
            case CLASS -> "class";
            case FIELD -> "field";
            case METHOD -> "method";
            case PARAMETER -> "parameter";
            case UNKNOWN -> "";
        };
    }

    @Override
    public @NotNull String getDescriptiveName(@NotNull PsiElement element) {
        return element.getText();
    }

    @Override
    public @NotNull String getNodeText(@NotNull PsiElement element, boolean useFullName) {
        return element.getText();
    }
}
