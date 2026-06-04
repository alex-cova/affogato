package dev.affogato.intellij.psi;

import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import dev.affogato.intellij.AffogatoFileType;
import org.jetbrains.annotations.NotNull;

public final class AffogatoElementFactory {
    private AffogatoElementFactory() {
    }

    public static @NotNull Identifier createIdentifier(@NotNull com.intellij.openapi.project.Project project, @NotNull String name) {
        AffogatoFile file = (AffogatoFile) PsiFileFactory.getInstance(project)
                .createFileFromText("Rename.aff", AffogatoFileType.INSTANCE, "class " + name + " {}");
        ClassDecl classDecl = PsiTreeUtil.findChildOfType(file, ClassDecl.class);
        if (classDecl == null) {
            throw new IllegalArgumentException("Invalid Affogato identifier: " + name);
        }
        return classDecl.getIdentifier();
    }

    public static @NotNull PsiElement createIdentifierToken(@NotNull com.intellij.openapi.project.Project project, @NotNull String name) {
        AffogatoFile file = (AffogatoFile) PsiFileFactory.getInstance(project)
                .createFileFromText("Rename.aff", AffogatoFileType.INSTANCE, "class Dummy { func f() { " + name + " } }");
        PsiElement token = findIdentifierToken(file, name);
        if (token == null) {
            throw new IllegalArgumentException("Invalid Affogato identifier: " + name);
        }
        return token;
    }

    private static PsiElement findIdentifierToken(PsiElement element, String name) {
        if (element.getNode() != null
                && element.getNode().getElementType() == AffogatoTypes.ID
                && element.getText().equals(name)
                && !(element.getParent() instanceof Identifier)) {
            return element;
        }
        for (PsiElement child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            PsiElement found = findIdentifierToken(child, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
