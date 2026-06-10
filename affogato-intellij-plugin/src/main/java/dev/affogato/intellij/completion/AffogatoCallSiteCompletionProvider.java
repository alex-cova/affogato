package dev.affogato.intellij.completion;

import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.PlainPrefixMatcher;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.ProcessingContext;
import dev.affogato.intellij.completion.lookup.AffogatoLookupElements;
import dev.affogato.intellij.project.AffogatoJavaIndex;
import dev.affogato.intellij.psi.AffogatoTextUtil;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.Set;

import static com.intellij.codeInsight.completion.CompletionUtil.DUMMY_IDENTIFIER_TRIMMED;

final class AffogatoCallSiteCompletionProvider extends CompletionProvider<CompletionParameters> {
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

        AffogatoCallSite.Site site = AffogatoCallSite.at(position, parameters.getOffset());
        if (site == null) {
            return;
        }

        if (result.getPrefixMatcher().getPrefix().contains(DUMMY_IDENTIFIER_TRIMMED)) {
            result = result.withPrefixMatcher(new PlainPrefixMatcher(""));
        }

        Project project = position.getProject();
        PsiFile file = parameters.getOriginalFile();
        GlobalSearchScope scope = AffogatoJavaIndex.scopeFor(file);
        Set<String> used = AffogatoCallSite.usedNamedArguments(site.callGroup());
        Set<String> seen = new LinkedHashSet<>();

        for (AffogatoCallSite.CallableParameter parameter : AffogatoCallSite.callableParameters(site, position, file, scope)) {
            if (used.contains(parameter.name()) || !seen.add(parameter.name())) {
                continue;
            }
            result.addElement(AffogatoLookupElements.namedArgument(parameter.name(), parameter.typeText()));
        }
    }
}
