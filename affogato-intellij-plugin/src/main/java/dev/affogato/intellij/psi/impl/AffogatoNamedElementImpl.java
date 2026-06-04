package dev.affogato.intellij.psi.impl;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import dev.affogato.intellij.psi.Identifier;
import dev.affogato.intellij.psi.AffogatoElementFactory;
import dev.affogato.intellij.psi.AffogatoNamedElement;
import dev.affogato.intellij.psi.AffogatoPsiUtil;
import dev.affogato.intellij.references.AffogatoReference;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class AffogatoNamedElementImpl extends ASTWrapperPsiElement implements AffogatoNamedElement {
    protected AffogatoNamedElementImpl(@NotNull ASTNode node) {
        super(node);
    }

    @Override
    public @Nullable PsiElement getNameIdentifier() {
        return this;
    }

    @Override
    public String getName() {
        return getText();
    }

    @Override
    public PsiElement setName(@NotNull String name) {
        Identifier identifier = AffogatoElementFactory.createIdentifier(getProject(), name);
        return replace(identifier);
    }

    @Override
    public PsiReference @NotNull [] getReferences() {
        if (!AffogatoPsiUtil.canHaveReference(this)) {
            return PsiReference.EMPTY_ARRAY;
        }
        return new PsiReference[]{new AffogatoReference(this)};
    }

    @Override
    public @Nullable PsiReference getReference() {
        PsiReference[] references = getReferences();
        return references.length == 0 ? null : references[0];
    }
}
