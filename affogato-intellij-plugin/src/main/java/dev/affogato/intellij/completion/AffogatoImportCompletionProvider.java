package dev.affogato.intellij.completion;

import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.PlainPrefixMatcher;

import static com.intellij.codeInsight.completion.CompletionUtil.DUMMY_IDENTIFIER_TRIMMED;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import dev.affogato.intellij.completion.lookup.AffogatoLookupElements;
import dev.affogato.intellij.project.AffogatoJavaIndex;
import dev.affogato.intellij.psi.AffogatoImports;
import dev.affogato.intellij.psi.AffogatoSymbols;
import dev.affogato.intellij.psi.AffogatoTextUtil;
import dev.affogato.intellij.psi.AffogatoTypes;
import dev.affogato.intellij.psi.Identifier;
import dev.affogato.intellij.psi.ImportDecl;
import dev.affogato.intellij.psi.QualifiedName;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class AffogatoImportCompletionProvider extends CompletionProvider<CompletionParameters> {
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
        if (completionContext.kind() != AffogatoCompletionContext.Kind.IMPORT) {
            return;
        }

        ImportDecl importDecl = PsiTreeUtil.getParentOfType(position, ImportDecl.class);
        if (importDecl == null) {
            return;
        }

        ImportSite site = importSite(position, importDecl);
        if (site == null) {
            return;
        }

        // Completion inserts a dummy identifier at the caret; ignore it when matching lookups.
        String completionPrefix = result.getPrefixMatcher().getPrefix();
        if (isDummyIdentifier(completionPrefix)) {
            result = result.withPrefixMatcher(new PlainPrefixMatcher(site.classPrefix()));
        }

        Project project = position.getProject();
        GlobalSearchScope scope = AffogatoJavaIndex.scopeFor(parameters.getOriginalFile());
        Set<String> seen = new LinkedHashSet<>();

        for (AffogatoSymbols.TopLevelType type : AffogatoSymbols.allTopLevelTypes(project)) {
            if (!site.packagePrefix().isBlank() && !type.packageName().equals(site.packagePrefix())) {
                continue;
            }
            String fqcn = AffogatoImports.qualifiedName(type);
            if (seen.add(fqcn)) {
                result.addElement(AffogatoLookupElements.importedType(fqcn, type));
            }
        }

        for (PsiClass psiClass : AffogatoJavaIndex.classesMatchingPrefix(
                project,
                scope,
                site.packagePrefix(),
                site.classPrefix()
        )) {
            String fqcn = psiClass.getQualifiedName();
            if (fqcn != null && seen.add(fqcn)) {
                result.addElement(AffogatoLookupElements.javaImportType(psiClass));
            }
        }
    }

    private static @Nullable ImportSite importSite(@NotNull PsiElement position, @NotNull ImportDecl importDecl) {
        QualifiedName qualifiedName = importDecl.getQualifiedName();
        List<Identifier> identifiers = qualifiedName.getIdentifierList();
        if (identifiers.isEmpty()) {
            return new ImportSite("", "");
        }

        if (position.getNode() != null && position.getNode().getElementType() == AffogatoTypes.DOT) {
            return new ImportSite(joinIdentifiers(identifiers), "");
        }

        Identifier current = currentIdentifier(position, qualifiedName);
        if (current == null) {
            current = identifiers.get(identifiers.size() - 1);
        }

        String packagePrefix = joinIdentifiersBefore(identifiers, current);
        String classPrefix = classPrefixAt(position, current);
        return new ImportSite(packagePrefix, classPrefix);
    }

    private static @NotNull String classPrefixAt(@NotNull PsiElement position, @NotNull Identifier current) {
        String text = current.getText();
        if (isDummyIdentifier(text)) {
            return "";
        }
        if (AffogatoTextUtil.previousNonWhitespaceChar(position) == '.' && text.isEmpty()) {
            return "";
        }
        return text;
    }

    private static boolean isDummyIdentifier(@NotNull String text) {
        return text.contains(DUMMY_IDENTIFIER_TRIMMED);
    }

    private static @NotNull String joinIdentifiersBefore(
            @NotNull List<Identifier> identifiers,
            @NotNull Identifier stopBefore
    ) {
        StringBuilder packagePrefix = new StringBuilder();
        for (Identifier identifier : identifiers) {
            if (identifier == stopBefore) {
                break;
            }
            if (packagePrefix.length() > 0) {
                packagePrefix.append('.');
            }
            packagePrefix.append(identifier.getText());
        }
        return packagePrefix.toString();
    }

    private static @Nullable Identifier currentIdentifier(@NotNull PsiElement position, @NotNull QualifiedName qualifiedName) {
        if (position instanceof Identifier identifier) {
            return PsiTreeUtil.isAncestor(qualifiedName, identifier, false) ? identifier : null;
        }
        Identifier identifier = PsiTreeUtil.getParentOfType(position, Identifier.class, false);
        if (identifier != null && PsiTreeUtil.isAncestor(qualifiedName, identifier, false)) {
            return identifier;
        }
        return null;
    }

    private static @NotNull String joinIdentifiers(@NotNull List<Identifier> identifiers) {
        StringBuilder joined = new StringBuilder();
        for (Identifier identifier : identifiers) {
            if (joined.length() > 0) {
                joined.append('.');
            }
            joined.append(identifier.getText());
        }
        return joined.toString();
    }

    private record ImportSite(@NotNull String packagePrefix, @NotNull String classPrefix) {
    }
}
