package dev.affogato.intellij.completion;

import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.affogato.intellij.completion.lookup.AffogatoLookupElements;
import dev.affogato.intellij.completion.lookup.AffogatoWeightedLookupElement;
import dev.affogato.intellij.psi.AffogatoSymbols;
import org.jetbrains.annotations.NotNull;

public final class AffogatoPolishCompletionTest extends BasePlatformTestCase {
    public void testSnippetLookupElementUsesShortcutText() {
        var snippet = AffogatoLookupElements.snippet(
                "sout",
                "println(...)",
                context -> {
                }
        );

        assertEquals("sout", snippet.getLookupString());
        assertTrue(AffogatoWeightedLookupElement.weightOf(snippet) > 0);
    }

    public void testSamePackageTypeWeightedHigherThanCrossPackageType() {
        myFixture.addFileToProject("Other.aff", """
                package other.pkg

                class Remote {
                }
                """);
        PsiFile current = myFixture.configureByText("App.aff", """
                package dev.affogato.test

                class Local {
                }

                class App {
                }
                """);
        AffogatoSymbols.TopLevelType local = AffogatoSymbols.allTopLevelTypes(getProject()).stream()
                .filter(type -> type.identifier().getText().equals("Local"))
                .findFirst()
                .orElseThrow();
        AffogatoSymbols.TopLevelType remote = AffogatoSymbols.allTopLevelTypes(getProject()).stream()
                .filter(type -> type.identifier().getText().equals("Remote"))
                .findFirst()
                .orElseThrow();

        int localWeight = AffogatoWeightedLookupElement.weightOf(
                AffogatoLookupElements.topLevelType(local, current)
        );
        int remoteWeight = AffogatoWeightedLookupElement.weightOf(
                AffogatoLookupElements.topLevelType(remote, current)
        );

        assertTrue(localWeight > remoteWeight);
    }
}
