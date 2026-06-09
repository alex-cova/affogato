package dev.affogato.intellij.references;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.impl.DebugUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public final class AffogatoReferenceTest extends BasePlatformTestCase {
    public void testClassReferenceResolvesAcrossFiles() {
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

        PsiElement resolved = referenceAtCaret().resolve();

        assertNotNull(resolved);
        assertEquals("Person", resolved.getText());
    }

    public void testRecordReferenceResolvesAcrossFiles() {
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

        PsiElement resolved = referenceAtCaret().resolve();

        assertNotNull(resolved);
        assertEquals("Coord", resolved.getText());
    }

    public void testEnumReferenceResolvesAcrossFiles() {
        myFixture.addFileToProject("Color.aff", """
                package dev.affogato.test

                enum Color {
                    RED, GREEN
                }
                """);
        myFixture.configureByText("App.aff", """
                package dev.affogato.test

                class App {
                    func main() {
                        println(Co<caret>lor.RED)
                    }
                }
                """);

        PsiElement resolved = referenceAtCaret().resolve();

        assertNotNull(resolved);
        assertEquals("Color", resolved.getText());
    }

    public void testInterfaceImplementationReferenceResolvesAcrossFiles() {
        myFixture.addFileToProject("Drawable.aff", """
                package dev.affogato.test

                interface Drawable {
                    func draw()
                }
                """);
        myFixture.configureByText("Rectangle.aff", """
                package dev.affogato.test

                class Rectangle: Draw<caret>able {
                    override func draw() {
                    }
                }
                """);

        PsiElement resolved = referenceAtCaret().resolve();

        assertNotNull(resolved);
        assertEquals("Drawable", resolved.getText());
    }

    public void testFieldReferenceResolvesFromLocalOwnerType() {
        myFixture.configureByText("Person.aff", """
                package dev.affogato.test

                class Person(var name: String!) {
                    func printName() {
                        let person = Person("Affogato")
                        println(person.nam<caret>e)
                    }
                }
                """);

        PsiElement resolved = referenceAtCaret().resolve();

        assertNotNull(resolved);
        assertEquals("name", resolved.getText());
    }

    public void testMethodReferenceResolvesInCurrentClass() {
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

        PsiElement resolved = referenceAtCaret().resolve();

        assertNotNull(resolved);
        assertEquals("label", resolved.getText());
    }

    public void testParameterReferenceResolvesInMethod() {
        myFixture.configureByText("App.aff", """
                package dev.affogato.test

                class App {
                    String echo(value: String) {
                        return valu<caret>e
                    }
                }
                """);

        PsiElement resolved = referenceAtCaret().resolve();

        assertNotNull(resolved);
        assertEquals("value", resolved.getText());
    }

    public void testRenameClassUpdatesConstructorReference() {
        PsiFile app = myFixture.addFileToProject("App.aff", """
                package dev.affogato.test

                class App {
                    func main() {
                        let person = Person()
                    }
                }
                """);
        myFixture.configureByText("Person.aff", """
                package dev.affogato.test

                class Per<caret>son {
                }
                """);

        myFixture.renameElementAtCaret("User");

        assertTrue(app.getText().contains("User()"));
        assertFalse(app.getText().contains("Person()"));
    }

    public void testRenameRecordUpdatesConstructorReference() {
        PsiFile app = myFixture.addFileToProject("App.aff", """
                package dev.affogato.test

                class App {
                    func main() {
                        let coord = new Coord(1, 2)
                    }
                }
                """);
        myFixture.configureByText("Coord.aff", """
                package dev.affogato.test

                record Co<caret>ord(x: int, y: int) {
                }
                """);

        myFixture.renameElementAtCaret("Point");

        assertTrue(app.getText().contains("Point(1, 2)"));
        assertFalse(app.getText().contains("Coord(1, 2)"));
    }

    public void testImportedClassReferenceResolvesAcrossPackages() {
        myFixture.addFileToProject("Person.aff", """
                package dev.affogato.other

                class Person {
                }
                """);
        myFixture.configureByText("App.aff", """
                package dev.affogato.test

                import dev.affogato.other.Person

                class App {
                    func main() {
                        let person = Per<caret>son()
                    }
                }
                """);

        PsiElement resolved = referenceAtCaret().resolve();

        assertNotNull(resolved);
        assertEquals("Person", resolved.getText());
    }

    public void testModernSyntaxParsesWithoutPsiErrors() {
        PsiFile file = myFixture.configureByText("Modern.aff", """
                package dev.affogato.test

                import static dev.affogato.test.JavaApi.identity

                @Deprecated
                enum Color {
                    RED, GREEN
                }

                @SuppressWarnings("unused")
                record Coord(x: int, y: int): Drawable {
                    sum(): int {
                        return x + y
                    }
                }

                interface Drawable {
                    func draw()
                    default func label() {
                        println("Drawable")
                    }
                }

                class Rectangle: Drawable {
                    override func draw() {
                        for color in [Color.RED, Color.GREEN] {
                            println(color)
                        }

                        var counter = 0
                        while counter < 2 {
                            counter += 1
                        }

                        try {
                            switch counter {
                                case 0 -> println("zero")
                                default -> println(identity(value = "many"))
                            }
                        } catch (RuntimeException | IllegalArgumentException e) {
                            throw e
                        } finally {
                            println(new Coord(1, 2))
                        }
                    }
                }
                """);

        assertNoPsiErrors(file);
    }

    private PsiReference referenceAtCaret() {
        PsiReference reference = myFixture.getReferenceAtCaretPosition();
        if (reference == null) {
            PsiElement element = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
            fail("No reference at caret. Element tree: " + elementTree(element)
                    + " File tree: " + DebugUtil.psiToString(myFixture.getFile(), true));
        }
        return reference;
    }

    private void assertNoPsiErrors(PsiFile file) {
        PsiErrorElement error = PsiTreeUtil.findChildOfType(file, PsiErrorElement.class);
        if (error != null) {
            fail("Unexpected parse error: " + error.getErrorDescription()
                    + " near '" + error.getText().replace("\n", "\\n") + "'"
                    + " File tree: " + DebugUtil.psiToString(file, true));
        }
    }

    private String elementTree(PsiElement element) {
        StringBuilder out = new StringBuilder();
        PsiElement cursor = element;
        while (cursor != null && cursor != myFixture.getFile()) {
            out.append(cursor.getClass().getName())
                    .append(" text='")
                    .append(cursor.getText().replace("\n", "\\n"))
                    .append("' refs=")
                    .append(cursor.getReferences().length)
                    .append(" | ");
            cursor = cursor.getParent();
        }
        return out.toString();
    }
}
