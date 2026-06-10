package dev.affogato.intellij.formatter;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public final class AffogatoFormatterIdempotentTest extends BasePlatformTestCase {
    public void testIdiomaticSourceIsStable() {
        String src = """
                package com.alexcova.teenyjson

                class Buffer {
                    let json: String
                    var index: int

                    init(json: String) {
                        this.json = json
                        this.index = 0
                    }

                    func last(): char {
                        return json.charAt(index - 1)
                    }

                    func next(): boolean {
                        if index + 1 >= json.length() {
                            return false
                        } else {
                            index = index + 1
                            return true
                        }
                    }
                }
                """;
        PsiFile file = myFixture.configureByText("Buffer.aff", src);
        WriteCommandAction.runWriteCommandAction(getProject(), (Runnable) () ->
                CodeStyleManager.getInstance(getProject()).reformat(file));
        assertEquals(src, file.getText());
    }
}
