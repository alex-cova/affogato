package dev.affogato.intellij.completion;

import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.affogato.intellij.psi.AffogatoFile;
import dev.affogato.intellij.psi.AffogatoImports;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class AffogatoImportCompletionTest extends LightJavaCodeInsightFixtureTestCase {
    public void testAffogatoImportQualifiedNameCompletion() {
        myFixture.addFileToProject("Person.aff", """
                package dev.affogato.other

                class Person {
                }
                """);
        myFixture.configureByText("App.aff", """
                package dev.affogato.test

                import dev.affogato.other.P<caret>erson

                class App {
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "Person");
    }

    public void testJavaImportQualifiedNameCompletion() {
        myFixture.configureByText("App.aff", """
                package dev.affogato.test

                import java.util.L<caret>ist

                class App {
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "List");
    }

    public void testJavaImportPackageMemberCompletion() {
        myFixture.configureByText("App.aff", """
                package dev.affogato.test

                import java.util.<caret>

                class App {
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "List", "Map");
    }

    public void testAddImportInsertsStatement() {
        PsiFile file = myFixture.configureByText("App.aff", """
                package dev.affogato.test

                class App {
                }
                """);

        AffogatoImports.addImport((AffogatoFile) file, "dev.affogato.other.Person");

        assertTrue(file.getText().contains("import dev.affogato.other.Person"));
    }

    public void testJavaTypeSymbolCompletionIncludesClasspathType() {
        myFixture.configureByText("App.aff", """
                package dev.affogato.test

                class App {
                    func main() {
                        let values = Arr<caret>
                    }
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "ArrayList");
    }

    private static List<String> lookupStrings(LookupElement[] elements) {
        return Arrays.stream(elements).map(LookupElement::getLookupString).filter(Objects::nonNull).toList();
    }
}
