package dev.affogato.intellij;

import com.intellij.lang.BracePair;
import com.intellij.lang.PairedBraceMatcher;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import dev.affogato.intellij.psi.AffogatoTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class AffogatoBraceMatcher implements PairedBraceMatcher {
    private static final BracePair[] PAIRS = new BracePair[]{
            new BracePair(AffogatoTypes.LBRACE, AffogatoTypes.RBRACE, true),
            new BracePair(AffogatoTypes.LPAREN, AffogatoTypes.RPAREN, false),
            new BracePair(AffogatoTypes.LBRACK, AffogatoTypes.RBRACK, false),
    };

    @Override
    public BracePair @NotNull [] getPairs() {
        return PAIRS;
    }

    @Override
    public boolean isPairedBracesAllowedBeforeType(@NotNull IElementType lbraceType, @Nullable IElementType contextType) {
        return true;
    }

    @Override
    public int getCodeConstructStart(PsiFile file, int openingBraceOffset) {
        return openingBraceOffset;
    }
}
