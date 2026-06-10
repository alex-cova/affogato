package dev.affogato.intellij.completion;

import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.affogato.intellij.psi.AffogatoFile;
import dev.affogato.intellij.psi.AffogatoImports;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class AffogatoImportCompletionTest extends BasePlatformTestCase {
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

    public void testAffogatoImportPackageMemberCompletion() {
        myFixture.addFileToProject("Person.aff", """
                package dev.affogato.other

                class Person {
                }
                """);
        myFixture.addFileToProject("Place.aff", """
                package dev.affogato.other

                class Place {
                }
                """);
        myFixture.configureByText("App.aff", """
                package dev.affogato.test

                import dev.affogato.other.<caret>

                class App {
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "Person", "Place");
    }

    public void testJavaSourceImportQualifiedNameCompletion() {
        myFixture.addFileToProject("com/example/util/Util.java", """
                package com.example.util;

                public class Util {
                }
                """);
        myFixture.configureByText("App.aff", """
                package dev.affogato.test

                import com.example.util.U<caret>til

                class App {
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "Util");
    }

    public void testJavaSourceImportPackageMemberCompletion() {
        myFixture.addFileToProject("com/example/util/Alpha.java", """
                package com.example.util;

                public class Alpha {
                }
                """);
        myFixture.addFileToProject("com/example/util/Beta.java", """
                package com.example.util;

                public class Beta {
                }
                """);
        myFixture.configureByText("App.aff", """
                package dev.affogato.test

                import com.example.util.<caret>

                class App {
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "Alpha", "Beta");
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

    public void testAutoImportAddsStatementForCrossPackageType() {
        myFixture.addFileToProject("Person.aff", """
                package dev.affogato.other

                class Person {
                }
                """);
        PsiFile file = myFixture.configureByText("App.aff", """
                package dev.affogato.test

                class App {
                    func main() {
                        let person = Person()
                    }
                }
                """);

        AffogatoImports.addImport((AffogatoFile) file, "dev.affogato.other.Person");

        assertTrue(file.getText().contains("import dev.affogato.other.Person"));
    }

    private static List<String> lookupStrings(LookupElement[] elements) {
        return Arrays.stream(elements).map(LookupElement::getLookupString).filter(Objects::nonNull).toList();
    }
}
