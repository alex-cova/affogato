package dev.affogato.intellij.completion;

import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.PlainPrefixMatcher;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import dev.affogato.intellij.completion.lookup.AffogatoLookupElements;
import dev.affogato.intellij.project.AffogatoJavaIndex;
import dev.affogato.intellij.psi.AffogatoSymbols;
import dev.affogato.intellij.psi.AffogatoTextUtil;
import dev.affogato.intellij.psi.ClassBody;
import dev.affogato.intellij.psi.ClassDecl;
import dev.affogato.intellij.psi.Identifier;
import dev.affogato.intellij.psi.InterfaceDecl;
import dev.affogato.intellij.psi.MethodSignature;
import dev.affogato.intellij.psi.RecordDecl;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.Set;

import static com.intellij.codeInsight.completion.CompletionUtil.DUMMY_IDENTIFIER_TRIMMED;

final class AffogatoSymbolCompletionProvider extends CompletionProvider<CompletionParameters> {
    @Override
    protected void addCompletions(
            @NotNull CompletionParameters parameters,
            @NotNull ProcessingContext context,
            @NotNull CompletionResultSet result
    ) {
        addCompletions(
                parameters,
                result,
                AffogatoCompletionContext.isErrorTreeContext(
                        parameters.getPosition(),
                        parameters.getOffset()
                )
        );
    }

    private static void addCompletions(
            @NotNull CompletionParameters parameters,
            @NotNull CompletionResultSet result,
            boolean lenient
    ) {
        PsiElement position = parameters.getPosition();
        if (AffogatoTextUtil.isInLiteralOrComment(position)) {
            return;
        }

        AffogatoCompletionContext completionContext = AffogatoCompletionContext.at(parameters);
        if (completionContext.kind() == AffogatoCompletionContext.Kind.IMPORT) {
            return;
        }

        String prefix = typePrefix(position, result);
        if (isDummyPrefix(result.getPrefixMatcher().getPrefix())) {
            result = result.withPrefixMatcher(new PlainPrefixMatcher(prefix));
        }
        addSnippets(result, position, prefix);

        if (!lenient && completionContext.kind() == AffogatoCompletionContext.Kind.DECLARATION_NAME) {
            return;
        }

        Project project = position.getProject();
        PsiFile file = parameters.getOriginalFile();
        GlobalSearchScope scope = AffogatoJavaIndex.scopeFor(file);
        Set<String> seen = new LinkedHashSet<>();

        if (completionContext.kind() == AffogatoCompletionContext.Kind.MEMBER) {
            return;
        }

        if (completionContext.expectsType() || completionContext.afterNew()) {
            addTopLevelTypes(result, project, file, seen);
        }

        if (completionContext.kind() == AffogatoCompletionContext.Kind.TYPE) {
            String typePrefix = typePrefix(position, result);
            if (!typePrefix.isEmpty() && Character.isUpperCase(typePrefix.charAt(0))) {
                addJavaTypes(result, project, file, scope, typePrefix, seen);
            }
            return;
        }

        if (completionContext.expectsCall()) {
            if (completionContext.nextChar() == '(') {
                addMethodOverloadCompletions(result, position, project, seen);
            }
            addIdentifiers(result, AffogatoSymbols.allMethodsInEnclosingClass(position), "method", seen);
            addIdentifiers(result, AffogatoSymbols.allProjectMethods(project), "method", seen);
            return;
        }

        addIdentifiers(result, AffogatoSymbols.allParametersInScope(position), "parameter", seen);
        addIdentifiers(result, AffogatoSymbols.allLocalsInScope(position), "local", seen);
        addIdentifiers(result, AffogatoSymbols.allFieldsInEnclosingClass(position), "field", seen);

        if (completionContext.afterNew()
                || (!prefix.isEmpty() && Character.isUpperCase(prefix.charAt(0)))) {
            addTopLevelTypes(result, project, file, seen);
            addJavaTypes(result, project, file, scope, prefix, seen);
        }
    }

    private static void addSnippets(
            @NotNull CompletionResultSet result,
            @NotNull PsiElement position,
            @NotNull String prefix
    ) {
        CompletionResultSet snippets = result.withPrefixMatcher(new PlainPrefixMatcher(prefix));
        if (matchesSnippet(prefix, "sout")) {
            snippets.addElement(AffogatoLookupElements.snippet(
                    "sout",
                    "println(...)",
                    AffogatoSnippetInsertHandlers::insertPrintln
            ));
        }
        if (matchesSnippet(prefix, "main") && isClassBodyPosition(position)) {
            snippets.addElement(AffogatoLookupElements.snippet(
                    "main",
                    "func main() { ... }",
                    AffogatoSnippetInsertHandlers::insertMainSkeleton
            ));
        }
    }

    private static boolean isClassBodyPosition(@NotNull PsiElement position) {
        return PsiTreeUtil.getParentOfType(position, ClassBody.class) != null
                || PsiTreeUtil.getParentOfType(position, RecordDecl.class) != null;
    }

    private static boolean matchesSnippet(@NotNull String prefix, @NotNull String snippetKey) {
        return snippetKey.startsWith(prefix) || prefix.startsWith(snippetKey);
    }

    private static @NotNull String typePrefix(@NotNull PsiElement position, @NotNull CompletionResultSet result) {
        String text = position.getText();
        if (isDummyPrefix(text)) {
            text = "";
        }
        if (!text.isEmpty()) {
            return text;
        }
        String matcherPrefix = result.getPrefixMatcher().getPrefix();
        return isDummyPrefix(matcherPrefix) ? "" : matcherPrefix;
    }

    private static boolean isDummyPrefix(@NotNull String text) {
        return text.contains(DUMMY_IDENTIFIER_TRIMMED);
    }

    private static void addTopLevelTypes(
            @NotNull CompletionResultSet result,
            @NotNull Project project,
            @NotNull PsiFile file,
            @NotNull Set<String> seen
    ) {
        for (AffogatoSymbols.TopLevelType type : AffogatoSymbols.allTopLevelTypes(project)) {
            if (seen.add(type.identifier().getText())) {
                result.addElement(AffogatoLookupElements.topLevelType(type, file));
            }
        }
    }

    private static void addJavaTypes(
            @NotNull CompletionResultSet result,
            @NotNull Project project,
            @NotNull PsiFile file,
            @NotNull GlobalSearchScope scope,
            @NotNull String prefix,
            @NotNull Set<String> seen
    ) {
        if (file instanceof dev.affogato.intellij.psi.AffogatoFile affogatoFile && !prefix.isBlank()) {
            PsiClass imported = AffogatoJavaIndex.findImportedClass(affogatoFile, project, scope, prefix);
            if (imported != null) {
                String name = imported.getName();
                if (name != null && seen.add(name)) {
                    result.addElement(AffogatoLookupElements.javaType(imported, file));
                }
            }
        }
        for (PsiClass psiClass : AffogatoJavaIndex.classesMatchingPrefix(project, scope, "", prefix)) {
            String name = psiClass.getName();
            if (name != null && seen.add(name)) {
                result.addElement(AffogatoLookupElements.javaType(psiClass, file));
            }
        }
    }

    private static void addIdentifiers(
            @NotNull CompletionResultSet result,
            @NotNull Iterable<Identifier> identifiers,
            @NotNull String kind,
            @NotNull Set<String> seen
    ) {
        for (Identifier identifier : identifiers) {
            if (seen.add(identifier.getText())) {
                result.addElement(AffogatoLookupElements.identifier(identifier, kind));
            }
        }
    }

    private static void addMethodOverloadCompletions(
            @NotNull CompletionResultSet result,
            @NotNull PsiElement position,
            @NotNull Project project,
            @NotNull Set<String> seen
    ) {
        PsiElement enclosing = PsiTreeUtil.getParentOfType(position, ClassDecl.class, RecordDecl.class, InterfaceDecl.class);
        if (enclosing != null) {
            for (Identifier method : AffogatoSymbols.allMethods(enclosing)) {
                for (MethodSignature signature : AffogatoSymbols.methodSignatures(enclosing, method.getText())) {
                    addMethodSignature(result, signature, seen);
                }
            }
        }
        Set<String> methodNames = new LinkedHashSet<>();
        for (Identifier method : AffogatoSymbols.allProjectMethods(project)) {
            methodNames.add(method.getText());
        }
        for (String methodName : methodNames) {
            for (MethodSignature signature : AffogatoSymbols.projectMethodSignatures(project, methodName)) {
                addMethodSignature(result, signature, seen);
            }
        }
    }

    private static void addMethodSignature(
            @NotNull CompletionResultSet result,
            @NotNull MethodSignature signature,
            @NotNull Set<String> seen
    ) {
        String signatureText = AffogatoLookupElements.affogatoMethodSignatureText(signature);
        String key = signature.getIdentifier().getText() + signatureText;
        if (seen.add(key)) {
            result.addElement(AffogatoLookupElements.affogatoMethod(signature, signatureText));
        }
    }
}
