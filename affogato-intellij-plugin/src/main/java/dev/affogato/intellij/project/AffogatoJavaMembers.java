package dev.affogato.intellij.project;

import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiTypes;
import dev.affogato.intellij.completion.lookup.AffogatoLookupElements;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.Set;

public final class AffogatoJavaMembers {
    private AffogatoJavaMembers() {
    }

    public static void addMembers(
            @NotNull CompletionResultSet result,
            @NotNull PsiClass psiClass,
            boolean staticOnly,
            @NotNull Set<String> seen
    ) {
        for (PsiField field : psiClass.getAllFields()) {
            if (!isVisible(field)) {
                continue;
            }
            if (staticOnly != field.hasModifierProperty(PsiModifier.STATIC)) {
                continue;
            }
            String name = field.getName();
            if (name != null && seen.add(name)) {
                result.addElement(AffogatoLookupElements.javaField(field));
            }
        }
        for (PsiMethod method : psiClass.getAllMethods()) {
            if (method.isConstructor() || !isVisible(method)) {
                continue;
            }
            if (staticOnly != method.hasModifierProperty(PsiModifier.STATIC)) {
                continue;
            }
            String name = method.getName();
            if (name != null && seen.add(name)) {
                result.addElement(AffogatoLookupElements.javaMethod(method));
            }
        }
    }

    public static @NotNull String memberType(@NotNull PsiClass owner, @NotNull String memberName) {
        PsiField field = owner.findFieldByName(memberName, true);
        if (field != null) {
            return field.getType().getPresentableText();
        }
        for (PsiMethod method : owner.findMethodsByName(memberName, true)) {
            if (method.isConstructor()) {
                continue;
            }
            if (method.getReturnType() != null && !PsiTypes.voidType().equals(method.getReturnType())) {
                return method.getReturnType().getPresentableText();
            }
        }
        return "";
    }

    private static boolean isVisible(@NotNull PsiField field) {
        return field.hasModifierProperty(PsiModifier.PUBLIC)
                || field.hasModifierProperty(PsiModifier.PROTECTED)
                || !field.hasModifierProperty(PsiModifier.PRIVATE);
    }

    private static boolean isVisible(@NotNull PsiMethod method) {
        return method.hasModifierProperty(PsiModifier.PUBLIC)
                || method.hasModifierProperty(PsiModifier.PROTECTED)
                || !method.hasModifierProperty(PsiModifier.PRIVATE);
    }
}
