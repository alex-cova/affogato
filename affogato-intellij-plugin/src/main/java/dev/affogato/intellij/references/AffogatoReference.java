package dev.affogato.intellij.references;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.util.IncorrectOperationException;
import dev.affogato.intellij.psi.Identifier;
import dev.affogato.intellij.psi.AffogatoElementFactory;
import dev.affogato.intellij.psi.AffogatoPsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class AffogatoReference extends PsiReferenceBase<PsiElement> {
    public AffogatoReference(@NotNull PsiElement element) {
        super(element, new TextRange(0, element.getTextLength()));
    }

    @Override
    public @Nullable PsiElement resolve() {
        return AffogatoPsiUtil.resolveReference(getElement());
    }

    @Override
    public PsiElement handleElementRename(@NotNull String newElementName) throws IncorrectOperationException {
        if (!AffogatoPsiUtil.isValidIdentifier(newElementName)) {
            throw new IncorrectOperationException("Invalid Affogato identifier: " + newElementName);
        }
        PsiElement element = getElement();
        if (element instanceof Identifier identifier) {
            return identifier.setName(newElementName);
        }
        return element.replace(AffogatoElementFactory.createIdentifierToken(element.getProject(), newElementName));
    }
}
