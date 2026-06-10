package dev.affogato.intellij.completion.lookup;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiClass;
import dev.affogato.intellij.completion.AffogatoCompletionWeights;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
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
import java.util.List;

public final class AffogatoLookupElements {
    private AffogatoLookupElements() {
    }

    public static @NotNull LookupElement keyword(@NotNull String keyword) {
        return weighted(
                LookupElementBuilder.create(keyword).withTypeText("keyword", true),
                AffogatoCompletionWeights.KEYWORD
        );
    }

    public static @NotNull LookupElement snippet(
            @NotNull String lookupString,
            @NotNull String typeText,
            @NotNull SnippetInsertHandler insertHandler
    ) {
        LookupElementBuilder builder = LookupElementBuilder.create(lookupString)
                .withTypeText(typeText, true)
                .withInsertHandler((context, item) -> insertHandler.insert(context));
        return AffogatoWeightedLookupElement.wrap(builder, AffogatoCompletionWeights.SNIPPET);
    }

    public static @NotNull LookupElement topLevelType(
            @NotNull AffogatoSymbols.TopLevelType type,
            @NotNull PsiFile file
    ) {
        String simpleName = type.identifier().getText();
        String fqcn = AffogatoImports.qualifiedName(type);
        int weight = typeWeight(file, type);
        LookupElementBuilder builder = LookupElementBuilder.create(type.identifier(), simpleName)
                .withIcon(iconFor(type.kind()))
                .withTypeText(fqcn, true);
        if (file instanceof AffogatoFile affogatoFile
                && !AffogatoImports.isAccessibleWithoutImport(affogatoFile, fqcn)) {
            return weighted(AffogatoAddImportInsertHandler.withAutoImport(builder, fqcn), weight);
        }
        return weighted(builder, weight);
    }

    public static @NotNull LookupElement importedType(
            @NotNull String qualifiedName,
            @NotNull AffogatoSymbols.TopLevelType type
    ) {
        String simpleName = type.identifier().getText();
        return weighted(
                LookupElementBuilder.create(qualifiedName, simpleName)
                        .withIcon(iconFor(type.kind()))
                        .withTypeText(qualifiedName, true),
                AffogatoCompletionWeights.PROJECT_TYPE
        );
    }

    public static @NotNull LookupElement javaImportType(@NotNull PsiClass psiClass) {
        String fqcn = psiClass.getQualifiedName();
        String simpleName = psiClass.getName() == null ? fqcn : psiClass.getName();
        return weighted(
                LookupElementBuilder.create(psiClass, simpleName)
                        .withIcon(AllIcons.Nodes.Class)
                        .withTypeText(fqcn, true),
                AffogatoCompletionWeights.IMPORTED_JAVA
        );
    }

    public static @NotNull LookupElement javaType(@NotNull PsiClass psiClass, @NotNull PsiFile file) {
        String fqcn = psiClass.getQualifiedName();
        String simpleName = psiClass.getName() == null ? fqcn : psiClass.getName();
        int weight = javaTypeWeight(file, psiClass);
        LookupElementBuilder builder = LookupElementBuilder.create(psiClass, simpleName)
                .withIcon(AllIcons.Nodes.Class)
                .withTypeText(fqcn, true);
        if (file instanceof AffogatoFile affogatoFile && fqcn != null
                && !AffogatoImports.isAccessibleWithoutImport(affogatoFile, fqcn)) {
            return weighted(AffogatoAddImportInsertHandler.withAutoImport(builder, fqcn), weight);
        }
        return weighted(builder, weight);
    }

    public static @NotNull LookupElement javaField(@NotNull PsiField field) {
        String name = field.getName();
        return weighted(
                LookupElementBuilder.create(field, name == null ? "" : name)
                        .withIcon(AllIcons.Nodes.Field)
                        .withTypeText(field.getType().getPresentableText(), true),
                AffogatoCompletionWeights.ENCLOSING_MEMBER
        );
    }

    public static @NotNull LookupElement javaMethod(@NotNull PsiMethod method) {
        String name = method.getName();
        return weighted(
                LookupElementBuilder.create(method, name == null ? "" : name)
                        .withIcon(AllIcons.Nodes.Method)
                        .withTypeText(methodSignature(method), true),
                AffogatoCompletionWeights.ENCLOSING_MEMBER
        );
    }

    public static @NotNull LookupElement identifier(@NotNull Identifier identifier, @NotNull String typeText) {
        return weighted(
                LookupElementBuilder.create(identifier, identifier.getText())
                        .withIcon(iconFor(identifier))
                        .withTypeText(typeText, true),
                identifierWeight(typeText)
        );
    }

    public static @NotNull LookupElement affogatoMethod(
            @NotNull MethodSignature signature,
            @NotNull String signatureText
    ) {
        return weighted(
                LookupElementBuilder.create(signature.getIdentifier(), signature.getIdentifier().getText())
                        .withIcon(AllIcons.Nodes.Method)
                        .withTypeText(signatureText, true),
                AffogatoCompletionWeights.ENCLOSING_MEMBER
        );
    }

    public static @NotNull String affogatoMethodSignatureText(@NotNull MethodSignature signature) {
        StringBuilder text = new StringBuilder("(");
        List<Parameter> parameters = AffogatoSymbols.parameters(signature);
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) {
                text.append(", ");
            }
            Parameter parameter = parameters.get(i);
            String type = parameter.getTypeRef() == null ? "" : parameter.getTypeRef().getText();
            text.append(type);
        }
        text.append(')');
        return text.toString();
    }

    public static @NotNull LookupElement namedArgument(@NotNull String name, @NotNull String typeText) {
        return weighted(
                LookupElementBuilder.create(name)
                        .withIcon(AllIcons.Nodes.Parameter)
                        .withTypeText(typeText, true)
                        .withInsertHandler((context, item) -> {
                    Document document = context.getDocument();
                    int tail = context.getTailOffset();
                    if (tail >= document.getTextLength() || document.getText().charAt(tail) != ':') {
                        document.insertString(tail, ": ");
                    }
                }),
                AffogatoCompletionWeights.NAMED_ARGUMENT
        );
    }

    @FunctionalInterface
    public interface SnippetInsertHandler {
        void insert(@NotNull InsertionContext context);
    }

    private static int typeWeight(@NotNull PsiFile file, @NotNull AffogatoSymbols.TopLevelType type) {
        if (file instanceof AffogatoFile affogatoFile) {
            String fqcn = AffogatoImports.qualifiedName(type);
            if (AffogatoImports.isAccessibleWithoutImport(affogatoFile, fqcn)) {
                return AffogatoCompletionWeights.SAME_PACKAGE_TYPE;
            }
        }
        return AffogatoCompletionWeights.PROJECT_TYPE;
    }

    private static int javaTypeWeight(@NotNull PsiFile file, @NotNull PsiClass psiClass) {
        if (file instanceof AffogatoFile affogatoFile) {
            String fqcn = psiClass.getQualifiedName();
            if (fqcn != null && AffogatoImports.isAccessibleWithoutImport(affogatoFile, fqcn)) {
                return AffogatoCompletionWeights.IMPORTED_JAVA;
            }
        }
        return AffogatoCompletionWeights.JAVA_CLASSPATH;
    }

    private static int identifierWeight(@NotNull String kind) {
        return switch (kind) {
            case "parameter" -> AffogatoCompletionWeights.PARAMETER;
            case "local" -> AffogatoCompletionWeights.LOCAL;
            case "field", "method" -> AffogatoCompletionWeights.ENCLOSING_MEMBER;
            default -> AffogatoCompletionWeights.DEFAULT;
        };
    }

    private static @NotNull LookupElement weighted(@NotNull LookupElement delegate, int weight) {
        return AffogatoWeightedLookupElement.wrap(delegate, weight);
    }

    private static @NotNull LookupElement weighted(@NotNull LookupElementBuilder builder, int weight) {
        return AffogatoWeightedLookupElement.wrap(builder, weight);
    }

    private static @NotNull String methodSignature(@NotNull PsiMethod method) {
        StringBuilder signature = new StringBuilder("(");
        PsiParameter[] parameters = method.getParameterList().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) {
                signature.append(", ");
            }
            signature.append(parameters[i].getType().getPresentableText());
        }
        signature.append(')');
        return signature.toString();
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
