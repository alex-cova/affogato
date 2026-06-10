package dev.affogato.intellij.completion;

import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class AffogatoJavaCompletionTest extends BasePlatformTestCase {
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("java/util/ArrayList.java", """
                package java.util;

                public class ArrayList {
                    public boolean isEmpty() {
                        return true;
                    }
                }
                """);
        myFixture.addFileToProject("java/lang/System.java", """
                package java.lang;

                import java.io.PrintStream;

                public class System {
                    public static final PrintStream out = null;
                }
                """);
        myFixture.addFileToProject("java/io/PrintStream.java", """
                package java.io;

                public class PrintStream {
                    public void println(String value) {
                    }
                }
                """);
    }

    public void testJavaTypeCompletionWithImport() {
        myFixture.configureByText("App.aff", """
                package dev.affogato.test

                import java.util.ArrayList

                class App {
                    func main() {
                        let list = new <caret>ArrayList()
                    }
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "ArrayList");
    }

    public void testJavaInstanceMethodMemberCompletion() {
        myFixture.configureByText("App.aff", """
                package dev.affogato.test

                import java.util.ArrayList

                class App {
                    func main() {
                        let list = new ArrayList()
                        list.<caret>isEmpty()
                    }
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "isEmpty");
    }

    public void testJavaStaticFieldMemberCompletion() {
        myFixture.configureByText("App.aff", """
                package dev.affogato.test

                class App {
                    func main() {
                        println(System.<caret>out)
                    }
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "out");
    }

    public void testJavaChainedMemberCompletion() {
        myFixture.configureByText("App.aff", """
                package dev.affogato.test

                class App {
                    func main() {
                        System.out.<caret>println("hi")
                    }
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "println");
    }

    public void testJavaMemberCompletionFromProjectSource() {
        myFixture.addFileToProject("com/example/util/Names.java", """
                package com.example.util;

                public class Names {
                    public String first() {
                        return "first";
                    }
                }
                """);
        myFixture.configureByText("App.aff", """
                package dev.affogato.test

                import com.example.util.Names

                class App {
                    func main() {
                        let names = new Names()
                        names.<caret>first()
                    }
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "first");
    }

    private static List<String> lookupStrings(LookupElement[] elements) {
        return Arrays.stream(elements).map(LookupElement::getLookupString).filter(Objects::nonNull).toList();
    }
}
