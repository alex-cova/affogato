package dev.affogato.intellij.psi;

import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

public final class AffogatoTextUtil {
    private AffogatoTextUtil() {
    }

    public static char previousNonWhitespaceChar(@NotNull PsiElement element) {
        String text = element.getContainingFile().getText();
        int index = element.getTextRange().getStartOffset() - 1;
        while (index >= 0 && Character.isWhitespace(text.charAt(index))) {
            index--;
        }
        return index >= 0 ? text.charAt(index) : '\0';
    }

    public static char nextNonWhitespaceChar(@NotNull PsiElement element) {
        String text = element.getContainingFile().getText();
        int index = element.getTextRange().getEndOffset();
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index < text.length() ? text.charAt(index) : '\0';
    }

    public static @NotNull String previousWordBeforeDot(@NotNull PsiElement element) {
        String receiver = receiverExpressionBeforeDot(element);
        int dot = receiver.lastIndexOf('.');
        return dot >= 0 ? receiver.substring(dot + 1) : receiver;
    }

    public static @NotNull String receiverExpressionBeforeDot(@NotNull PsiElement element) {
        String text = element.getContainingFile().getText();
        int dotIndex = memberAccessDotIndex(text, element);
        if (dotIndex < 0) {
            return "";
        }
        int receiverEnd = dotIndex;
        if (receiverEnd > 0 && text.charAt(receiverEnd - 1) == '?') {
            receiverEnd--;
        }
        int receiverStart = expressionStartBefore(text, receiverEnd - 1);
        return text.substring(receiverStart, receiverEnd).trim();
    }

    public static boolean isMemberAccessPosition(@NotNull PsiElement element) {
        if (PsiTreeUtil.getParentOfType(element, ImportDecl.class) != null) {
            return false;
        }
        if (element.getNode() != null) {
            var type = element.getNode().getElementType();
            if (type == AffogatoTypes.DOT || type == AffogatoTypes.QUESTION_DOT) {
                return true;
            }
        }
        char previous = previousNonWhitespaceChar(element);
        return previous == '.' || previous == '?';
    }

    private static int memberAccessDotIndex(@NotNull String text, @NotNull PsiElement element) {
        int index = element.getTextRange().getStartOffset() - 1;
        if (element.getNode() != null) {
            var type = element.getNode().getElementType();
            if (type == AffogatoTypes.DOT || type == AffogatoTypes.QUESTION_DOT) {
                index = element.getTextRange().getStartOffset();
            }
        }
        while (index >= 0 && Character.isWhitespace(text.charAt(index))) {
            index--;
        }
        if (index < 0) {
            return -1;
        }
        if (text.charAt(index) == '?') {
            return index > 0 && text.charAt(index - 1) == '.' ? index - 1 : -1;
        }
        return text.charAt(index) == '.' ? index : -1;
    }

    private static int expressionStartBefore(@NotNull String text, int index) {
        while (index >= 0 && Character.isWhitespace(text.charAt(index))) {
            index--;
        }
        if (index < 0) {
            return 0;
        }

        int end = index + 1;
        if (endsWithKeyword(text, index, "this")) {
            return index - 3;
        }
        if (endsWithKeyword(text, index, "super")) {
            return index - 4;
        }

        while (index >= 0) {
            if (Character.isJavaIdentifierPart(text.charAt(index))) {
                index--;
                continue;
            }
            if (text.charAt(index) == '.') {
                index--;
                while (index >= 0 && Character.isWhitespace(text.charAt(index))) {
                    index--;
                }
                if (index >= 0 && Character.isJavaIdentifierPart(text.charAt(index))) {
                    continue;
                }
            }
            break;
        }
        return index + 1;
    }

    private static boolean endsWithKeyword(@NotNull String text, int index, @NotNull String keyword) {
        int start = index - keyword.length() + 1;
        if (start < 0 || !text.regionMatches(start, keyword, 0, keyword.length())) {
            return false;
        }
        return start == 0 || !Character.isJavaIdentifierPart(text.charAt(start - 1));
    }

    public static boolean isAfterNew(@NotNull PsiElement element) {
        String text = element.getContainingFile().getText();
        int index = element.getTextRange().getStartOffset() - 1;
        while (index >= 0 && Character.isWhitespace(text.charAt(index))) {
            index--;
        }
        if (index < 2) {
            return false;
        }
        int end = index + 1;
        while (index >= 0 && Character.isJavaIdentifierPart(text.charAt(index))) {
            index--;
        }
        return "new".equals(text.substring(index + 1, end));
    }

    public static boolean isInLiteralOrComment(@NotNull PsiElement element) {
        return PsiTreeUtil.findFirstParent(element, false, parent ->
                parent.getNode() != null && (
                        parent.getNode().getElementType() == AffogatoTypes.STRING
                                || parent.getNode().getElementType() == AffogatoTypes.LINE_COMMENT
                                || parent.getNode().getElementType() == AffogatoTypes.BLOCK_COMMENT
                )) != null;
    }
}
