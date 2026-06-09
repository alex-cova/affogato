package dev.affogato.intellij.completion;

import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.util.ProcessingContext;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import dev.affogato.intellij.completion.lookup.AffogatoLookupElements;
import dev.affogato.intellij.project.AffogatoJavaIndex;
import dev.affogato.intellij.psi.AffogatoSymbols;
import dev.affogato.intellij.psi.AffogatoTextUtil;
import dev.affogato.intellij.psi.Identifier;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.Set;

final class AffogatoSymbolCompletionProvider extends CompletionProvider<CompletionParameters> {
    @Override
    protected void addCompletions(
            @NotNull CompletionParameters parameters,
            @NotNull ProcessingContext context,
            @NotNull CompletionResultSet result
    ) {
        PsiElement position = parameters.getPosition();
        if (AffogatoTextUtil.isInLiteralOrComment(position)) {
            return;
        }

        AffogatoCompletionContext completionContext = AffogatoCompletionContext.at(parameters);
        if (completionContext.kind() == AffogatoCompletionContext.Kind.IMPORT
                || completionContext.kind() == AffogatoCompletionContext.Kind.DECLARATION_NAME) {
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
            String typePrefix = position.getText();
            if (!typePrefix.isEmpty() && Character.isUpperCase(typePrefix.charAt(0))) {
                addJavaTypes(result, project, file, scope, typePrefix, seen);
            }
            return;
        }

        if (completionContext.expectsCall()) {
            addIdentifiers(result, AffogatoSymbols.allMethodsInEnclosingClass(position), "method", seen);
            addIdentifiers(result, AffogatoSymbols.allProjectMethods(project), "method", seen);
            return;
        }

        addIdentifiers(result, AffogatoSymbols.allParametersInScope(position), "parameter", seen);
        addIdentifiers(result, AffogatoSymbols.allLocalsInScope(position), "local", seen);
        addIdentifiers(result, AffogatoSymbols.allFieldsInEnclosingClass(position), "field", seen);

        String prefix = position.getText();
        if (completionContext.afterNew()
                || (!prefix.isEmpty() && Character.isUpperCase(prefix.charAt(0)))) {
            addTopLevelTypes(result, project, file, seen);
            addJavaTypes(result, project, file, scope, prefix, seen);
        }
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
}
