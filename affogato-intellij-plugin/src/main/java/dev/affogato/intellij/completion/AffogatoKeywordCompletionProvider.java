package dev.affogato.intellij.completion;

import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import dev.affogato.intellij.completion.lookup.AffogatoLookupElements;
import dev.affogato.intellij.psi.AffogatoTextUtil;
import dev.affogato.intellij.psi.ClassBody;
import dev.affogato.intellij.psi.EnumBody;
import dev.affogato.intellij.psi.ImportDecl;
import dev.affogato.intellij.psi.InterfaceBody;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.Set;

final class AffogatoKeywordCompletionProvider extends CompletionProvider<CompletionParameters> {
    private static final Set<String> TOP_LEVEL_KEYWORDS = Set.of(
            "package", "import", "class", "record", "enum", "interface", "func"
    );
    private static final Set<String> CLASS_BODY_KEYWORDS = Set.of(
            "public", "private", "protected", "static", "override", "abstract", "var", "let", "func", "init"
    );
    private static final Set<String> INTERFACE_BODY_KEYWORDS = Set.of(
            "public", "private", "protected", "static", "default", "func"
    );
    private static final Set<String> STATEMENT_KEYWORDS = Set.of(
            "if", "else", "for", "while", "return", "throw", "break", "continue", "guard",
            "try", "catch", "finally", "switch", "case", "default", "let", "var", "new",
            "true", "false", "null", "super", "this", "assert", "not", "is", "as", "in"
    );
    private static final Set<String> IMPORT_KEYWORDS = Set.of("static");

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

        for (String keyword : keywordsFor(position, completionContext)) {
            var element = AffogatoLookupElements.keyword(keyword);
            result.addElement(element);
        }
    }

    private static @NotNull Set<String> keywordsFor(@NotNull PsiElement position, @NotNull AffogatoCompletionContext context) {
        if (context.kind() == AffogatoCompletionContext.Kind.IMPORT) {
            return IMPORT_KEYWORDS;
        }
        if (PsiTreeUtil.getParentOfType(position, InterfaceBody.class) != null) {
            return INTERFACE_BODY_KEYWORDS;
        }
        if (PsiTreeUtil.getParentOfType(position, ClassBody.class) != null
                || PsiTreeUtil.getParentOfType(position, EnumBody.class) != null) {
            return union(CLASS_BODY_KEYWORDS, STATEMENT_KEYWORDS);
        }
        if (PsiTreeUtil.getParentOfType(position, ImportDecl.class) != null) {
            return IMPORT_KEYWORDS;
        }
        if (isTopLevelPosition(position)) {
            return TOP_LEVEL_KEYWORDS;
        }
        return STATEMENT_KEYWORDS;
    }

    private static boolean isTopLevelPosition(@NotNull PsiElement position) {
        return PsiTreeUtil.getParentOfType(position, ClassBody.class) == null
                && PsiTreeUtil.getParentOfType(position, InterfaceBody.class) == null
                && PsiTreeUtil.getParentOfType(position, EnumBody.class) == null
                && PsiTreeUtil.getParentOfType(position, ImportDecl.class) == null;
    }

    private static @NotNull Set<String> union(@NotNull Set<String> first, @NotNull Set<String> second) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(first);
        merged.addAll(second);
        return merged;
    }
}
