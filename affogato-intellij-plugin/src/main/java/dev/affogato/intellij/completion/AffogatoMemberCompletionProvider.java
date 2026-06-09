package dev.affogato.intellij.completion;

import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.util.ProcessingContext;
import dev.affogato.intellij.completion.lookup.AffogatoLookupElements;
import dev.affogato.intellij.psi.AffogatoSymbols;
import dev.affogato.intellij.psi.AffogatoTextUtil;
import dev.affogato.intellij.psi.AffogatoTypeResolver;
import dev.affogato.intellij.psi.EnumDecl;
import dev.affogato.intellij.psi.Identifier;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.Set;

final class AffogatoMemberCompletionProvider extends CompletionProvider<CompletionParameters> {
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
        if (!AffogatoTextUtil.isMemberAccessPosition(position)) {
            return;
        }

        String receiver = AffogatoTextUtil.receiverExpressionBeforeDot(position);
        if (receiver.isBlank()) {
            return;
        }

        Project project = position.getProject();
        Set<String> seen = new LinkedHashSet<>();
        String ownerType = AffogatoTypeResolver.resolveExpressionType(position, receiver);
        if (!ownerType.isBlank()) {
            PsiElement ownerDecl = AffogatoTypeResolver.findTypeDecl(position, ownerType);
            if (ownerDecl instanceof EnumDecl enumDecl) {
                addIdentifiers(result, AffogatoSymbols.allEnumConstants(enumDecl), "enum constant", seen);
                return;
            }
            if (ownerDecl != null) {
                addIdentifiers(result, AffogatoSymbols.allFields(ownerDecl), "field", seen);
                addIdentifiers(result, AffogatoSymbols.allMethods(ownerDecl), "method", seen);
                return;
            }
        }

        addIdentifiers(result, AffogatoSymbols.allProjectFields(project), "field", seen);
        addIdentifiers(result, AffogatoSymbols.allProjectMethods(project), "method", seen);
    }

    private static void addIdentifiers(
            @NotNull CompletionResultSet result,
            @NotNull Iterable<Identifier> identifiers,
            @NotNull String kind,
            @NotNull Set<String> seen
    ) {
        for (Identifier identifier : identifiers) {
            if (seen.add(identifier.getText())) {
                LookupElementBuilder element = AffogatoLookupElements.identifier(identifier, kind);
                result.addElement(element);
            }
        }
    }
}
