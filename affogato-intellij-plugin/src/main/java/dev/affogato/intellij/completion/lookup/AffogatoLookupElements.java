package dev.affogato.intellij.completion.lookup;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import dev.affogato.intellij.completion.imports.AffogatoAddImportInsertHandler;
import dev.affogato.intellij.psi.AffogatoFile;
import dev.affogato.intellij.psi.AffogatoImports;
import dev.affogato.intellij.psi.AffogatoPsiUtil;
import dev.affogato.intellij.psi.AffogatoSymbols;
import dev.affogato.intellij.psi.Identifier;
import dev.affogato.intellij.psi.MethodSignature;
import dev.affogato.intellij.psi.Parameter;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

public final class AffogatoLookupElements {
    private AffogatoLookupElements() {
    }

    public static @NotNull LookupElementBuilder keyword(@NotNull String keyword) {
        return LookupElementBuilder.create(keyword).withTypeText("keyword", true);
    }

    public static @NotNull LookupElement topLevelType(
            @NotNull AffogatoSymbols.TopLevelType type,
            @NotNull PsiFile file
    ) {
        String simpleName = type.identifier().getText();
        String fqcn = AffogatoImports.qualifiedName(type);
        LookupElementBuilder builder = LookupElementBuilder.create(type.identifier(), simpleName)
                .withIcon(iconFor(type.kind()))
                .withTypeText(fqcn, true);
        if (file instanceof AffogatoFile affogatoFile
                && !AffogatoImports.isAccessibleWithoutImport(affogatoFile, fqcn)) {
            return AffogatoAddImportInsertHandler.withAutoImport(builder, fqcn);
        }
        return builder;
    }

    public static @NotNull LookupElementBuilder importedType(
            @NotNull String qualifiedName,
            @NotNull AffogatoSymbols.TopLevelType type
    ) {
        String simpleName = type.identifier().getText();
        return LookupElementBuilder.create(qualifiedName, simpleName)
                .withIcon(iconFor(type.kind()))
                .withTypeText(qualifiedName, true);
    }

    public static @NotNull LookupElementBuilder javaImportType(@NotNull PsiClass psiClass) {
        String fqcn = psiClass.getQualifiedName();
        String simpleName = psiClass.getName() == null ? fqcn : psiClass.getName();
        return LookupElementBuilder.create(psiClass, simpleName)
                .withIcon(AllIcons.Nodes.Class)
                .withTypeText(fqcn, true);
    }

    public static @NotNull LookupElement javaType(@NotNull PsiClass psiClass, @NotNull PsiFile file) {
        String fqcn = psiClass.getQualifiedName();
        String simpleName = psiClass.getName() == null ? fqcn : psiClass.getName();
        LookupElementBuilder builder = LookupElementBuilder.create(simpleName)
                .withIcon(AllIcons.Nodes.Class)
                .withTypeText(fqcn, true);
        if (file instanceof AffogatoFile affogatoFile && fqcn != null
                && !AffogatoImports.isAccessibleWithoutImport(affogatoFile, fqcn)) {
            return AffogatoAddImportInsertHandler.withAutoImport(builder, fqcn);
        }
        return builder;
    }

    public static @NotNull LookupElementBuilder identifier(@NotNull Identifier identifier, @NotNull String typeText) {
        return LookupElementBuilder.create(identifier, identifier.getText())
                .withIcon(iconFor(identifier))
                .withTypeText(typeText, true);
    }

    private static @NotNull Icon iconFor(@NotNull AffogatoSymbols.TopLevelKind kind) {
        return switch (kind) {
            case CLASS -> AllIcons.Nodes.Class;
            case RECORD -> AllIcons.Nodes.Record;
            case ENUM -> AllIcons.Nodes.Enum;
            case INTERFACE -> AllIcons.Nodes.Interface;
        };
    }

    private static @NotNull Icon iconFor(@NotNull Identifier identifier) {
        return switch (AffogatoPsiUtil.declarationKind(identifier)) {
            case CLASS -> AllIcons.Nodes.Class;
            case FIELD -> AllIcons.Nodes.Field;
            case METHOD -> AllIcons.Nodes.Method;
            case PARAMETER -> AllIcons.Nodes.Parameter;
            case UNKNOWN -> {
                PsiElement parent = identifier.getParent();
                if (parent instanceof Parameter) {
                    yield AllIcons.Nodes.Parameter;
                }
                if (parent instanceof MethodSignature) {
                    yield AllIcons.Nodes.Method;
                }
                yield AllIcons.Nodes.Variable;
            }
        };
    }
}
