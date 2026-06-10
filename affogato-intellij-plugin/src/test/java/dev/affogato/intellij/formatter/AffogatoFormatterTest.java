package dev.affogato.intellij.formatter;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public final class AffogatoFormatterTest extends BasePlatformTestCase {
    public void testSpaceBeforeOpeningBrace() {
        assertReformatted(
                "class Foo{\n}\n",
                "class Foo {\n}\n");
    }

    public void testElseFormattedOnClosingBraceLine() {
        assertReformatted(
                """
                        class App {
                            func test(): boolean {
                                if cond {
                                    return false
                                }else{
                                    return true
                                }
                            }
                        }
                        """,
                """
                        class App {
                            func test(): boolean {
                                if cond {
                                    return false
                                } else {
                                    return true
                                }
                            }
                        }
                        """);
    }

    public void testEmptyBraceStaysOnOneLine() {
        assertReformatted(
                "class Empty {}\n",
                "class Empty {}\n");
    }

    public void testUnaryMinusNotMangled() {
        assertReformatted(
                """
                        class App {
                            func read(): int {
                                return -1
                            }
                        }
                        """,
                """
                        class App {
                            func read(): int {
                                return -1
                            }
                        }
                        """);
    }

    public void testNestedBlockIndentation() {
        assertReformatted(
                """
                        class App {
                        func main() {
                        return
                        }
                        }
                        """,
                """
                        class App {
                            func main() {
                                return
                            }
                        }
                        """);
    }

    public void testSingleBlankLineBetweenMembers() {
        assertReformatted(
                """
                        class App {
                            let a: int



                            func one() {
                            }



                            func two() {
                            }
                        }
                        """,
                """
                        class App {
                            let a: int

                            func one() {
                            }

                            func two() {
                            }
                        }
                        """);
    }

    private void assertReformatted(String input, String expected) {
        PsiFile file = myFixture.configureByText("App.aff", input);
        WriteCommandAction.runWriteCommandAction(getProject(), (Runnable) () ->
                CodeStyleManager.getInstance(getProject()).reformat(file));
        assertEquals(expected, file.getText());
    }
}
