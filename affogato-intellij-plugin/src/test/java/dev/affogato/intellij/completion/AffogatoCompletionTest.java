package dev.affogato.intellij.completion;

import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class AffogatoCompletionTest extends BasePlatformTestCase {
    public void testTopLevelKeywordCompletion() {
        myFixture.configureByText("App.aff", "<caret>");

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "class", "import", "package", "record");
    }

    public void testStatementKeywordCompletion() {
        myFixture.configureByText("App.aff", """
                package dev.affogato.test

                class App {
                    func main() {
                        <caret>
                    }
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "if", "for", "return", "let", "var");
    }

    public void testClassTypeCompletionAcrossFiles() {
        myFixture.addFileToProject("Person.aff", """
                package dev.affogato.test

                class Person {
                }
                """);
        myFixture.configureByText("App.aff", """
                package dev.affogato.test

                class App {
                    func main() {
                        let person = Per<caret>son()
                    }
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "Person");
    }

    public void testRecordTypeCompletionAcrossFiles() {
        myFixture.addFileToProject("Coord.aff", """
                package dev.affogato.test

                record Coord(x: int, y: int) {
                }
                """);
        myFixture.configureByText("App.aff", """
                package dev.affogato.test

                class App {
                    func main() {
                        let coord = new Co<caret>ord(1, 2)
                    }
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "Coord");
    }

    public void testParameterCompletionInMethod() {
        myFixture.configureByText("App.aff", """
                package dev.affogato.test

                class App {
                    String echo(value: String) {
                        return valu<caret>e
                    }
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "value");
    }

    public void testMethodCompletionInCurrentClass() {
        myFixture.configureByText("App.aff", """
                package dev.affogato.test

                class App {
                    String label() {
                        return "label"
                    }

                    func main() {
                        println(labe<caret>l())
                    }
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "label");
    }

    public void testFieldCompletionFromLocalOwnerType() {
        myFixture.configureByText("Person.aff", """
                package dev.affogato.test

                class Person(var name: String!) {
                    func printName() {
                        let person = Person("Affogato")
                        println(person.nam<caret>e)
                    }
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "name");
    }

    public void testEnumConstantMemberCompletion() {
        myFixture.configureByText("App.aff", """
                package dev.affogato.test

                enum Color {
                    RED, GREEN
                }

                class App {
                    func main() {
                        println(Color.Re<caret>)
                    }
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "RED", "GREEN");
    }

    public void testEnumConstantCompletionAfterDot() {
        myFixture.configureByText("App.aff", """
                package dev.affogato.test

                enum Color {
                    RED, GREEN
                }

                class App {
                    func main() {
                        println(Color.<caret>)
                    }
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "RED", "GREEN");
    }

    public void testRecordFieldMemberCompletion() {
        myFixture.configureByText("App.aff", """
                package dev.affogato.test

                record Coord(x: int, y: int) {
                }

                class App {
                    func main() {
                        let coord = new Coord(1, 2)
                        println(coord.<caret>)
                    }
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "x", "y");
    }

    public void testThisMemberCompletion() {
        myFixture.configureByText("Person.aff", """
                package dev.affogato.test

                class Person(var name: String!) {
                    func printName() {
                        println(this.nam<caret>e)
                    }
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "name");
    }

    public void testSafeCallMemberCompletion() {
        myFixture.configureByText("Person.aff", """
                package dev.affogato.test

                class Person(var name: String!) {
                    func printName(other: Person?) {
                        println(other?.nam<caret>e)
                    }
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "name");
    }

    public void testQualifiedChainMemberCompletion() {
        myFixture.configureByText("App.aff", """
                package dev.affogato.test

                class Inner(var value: int) {
                }

                class Outer(var inner: Inner!) {
                }

                class App {
                    func main() {
                        let outer = Outer(Inner(1))
                        println(outer.inner.val<caret>ue)
                    }
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "value");
    }

    public void testTypePositionCompletion() {
        myFixture.addFileToProject("Drawable.aff", """
                package dev.affogato.test

                interface Drawable {
                    func draw()
                }
                """);
        myFixture.configureByText("Rectangle.aff", """
                package dev.affogato.test

                class Rectangle: Draw<caret>able {
                }
                """);

        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);

        assertNotNull(elements);
        assertContainsElements(lookupStrings(elements), "Drawable");
    }

    private static List<String> lookupStrings(LookupElement[] elements) {
        return Arrays.stream(elements).map(LookupElement::getLookupString).filter(Objects::nonNull).toList();
    }
}
