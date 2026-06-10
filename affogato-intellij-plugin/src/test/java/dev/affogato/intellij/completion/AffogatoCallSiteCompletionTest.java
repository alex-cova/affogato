package dev.affogato.intellij.completion;

import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class AffogatoCallSiteCompletionTest extends BasePlatformTestCase {
    public void testNamedArgumentCompletionInMethodCall() {
        myFixture.configureByText("App.aff", """
                package dev.affogato.test

                class App {
                    String echo(value: String, count: int) {
                        return value
                    }

                    func main() {
                        echo(<caret>)
                    }
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "value", "count");
    }

    public void testNamedArgumentCompletionSkipsUsedNames() {
        myFixture.configureByText("App.aff", """
                package dev.affogato.test

                class App {
                    String echo(value: String, count: int) {
                        return value
                    }

                    func main() {
                        echo(value: "hi", <caret>)
                    }
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "count");
        assertDoesntContain(lookupStrings(elements), "value");
    }

    public void testConstructorNamedArgumentCompletion() {
        myFixture.configureByText("App.aff", """
                package dev.affogato.test

                class Person(name: String, age: int) {
                }

                class App {
                    func main() {
                        new Person(<caret>)
                    }
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "name", "age");
    }

    public void testRecordConstructorNamedArgumentCompletion() {
        myFixture.configureByText("App.aff", """
                package dev.affogato.test

                record Point(x: int, y: int) {
                }

                class App {
                    func main() {
                        new Point(<caret>)
                    }
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "x", "y");
    }

    public void testMethodOverloadCompletionBeforeOpenParen() {
        myFixture.configureByText("App.aff", """
                package dev.affogato.test

                class App {
                    func format(value: String) {
                    }

                    func format(value: String, count: int) {
                    }

                    func main() {
                        for<caret>()
                    }
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        List<String> formatEntries = Arrays.stream(elements)
                .map(LookupElement::getLookupString)
                .filter(name -> Objects.equals(name, "format"))
                .toList();
        assertEquals(2, formatEntries.size());
    }

    public void testNoMethodListInsideCallParens() {
        myFixture.configureByText("App.aff", """
                package dev.affogato.test

                class App {
                    func other() {
                    }

                    String echo(value: String) {
                        return value
                    }

                    func main() {
                        echo(<caret>)
                    }
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "value");
    }

    private static @NotNull List<String> lookupStrings(@NotNull LookupElement[] elements) {
        return Arrays.stream(elements).map(LookupElement::getLookupString).toList();
    }
}
