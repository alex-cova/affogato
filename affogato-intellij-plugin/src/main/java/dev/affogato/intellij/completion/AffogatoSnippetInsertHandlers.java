package dev.affogato.intellij.completion;

import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.openapi.editor.Document;
import org.jetbrains.annotations.NotNull;

final class AffogatoSnippetInsertHandlers {
    private AffogatoSnippetInsertHandlers() {
    }

    static void insertPrintln(@NotNull InsertionContext context) {
        replaceAndMoveCaret(context, "println()", "println(".length());
    }

    static void insertMainSkeleton(@NotNull InsertionContext context) {
        String body = """
                func main() {
                }
                """;
        replaceAndMoveCaret(context, body, body.indexOf('\n') + 1);
    }

    private static void replaceAndMoveCaret(
            @NotNull InsertionContext context,
            @NotNull String text,
            int caretOffset
    ) {
        Document document = context.getDocument();
        int start = context.getStartOffset();
        int tail = context.getTailOffset();
        document.replaceString(start, tail, text);
        context.getEditor().getCaretModel().moveToOffset(start + caretOffset);
    }
}
