package dev.affogato.intellij.psi;

import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import dev.affogato.intellij.psi.CallGroup;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    public static @NotNull String calleeExpressionBeforeCall(@NotNull PsiElement position) {
        CallGroup callGroup = PsiTreeUtil.getParentOfType(position, CallGroup.class);
        if (callGroup == null) {
            return "";
        }
        String fileText = callGroup.getContainingFile().getText();
        int openParen = openParenOffset(callGroup);
        if (openParen < 0) {
            return "";
        }
        int calleeStart = expressionStartBefore(fileText, openParen - 1);
        return fileText.substring(calleeStart, openParen).trim();
    }

    public static boolean isConstructorCallee(@NotNull String calleeExpression) {
        return Character.isUpperCase(calleeExpression.charAt(0));
    }

    public static boolean isNamedArgumentCompletionPosition(@NotNull PsiElement position) {
        return isNamedArgumentCompletionPosition(position, position.getTextRange().getStartOffset());
    }

    public static boolean isNamedArgumentCompletionPosition(@NotNull PsiElement position, int offset) {
        CallGroup callGroup = PsiTreeUtil.getParentOfType(position, CallGroup.class);
        if (callGroup == null) {
            return false;
        }
        if (startsNestedCallee(position, callGroup, offset)) {
            return false;
        }
        char previous = previousNonWhitespaceChar(position);
        if (previous == ':') {
            return false;
        }
        char next = nextNonWhitespaceChar(position);
        if (next == ':') {
            return true;
        }
        return previous == '(' || previous == ',';
    }

    public static boolean isCompletingNestedCalleeName(@NotNull PsiElement position) {
        return isCompletingNestedCalleeName(position, position.getTextRange().getStartOffset());
    }

    public static boolean isCompletingNestedCalleeName(@NotNull PsiElement position, int offset) {
        CallGroup callGroup = PsiTreeUtil.getParentOfType(position, CallGroup.class);
        return callGroup != null && startsNestedCallee(position, callGroup, offset);
    }

    private static boolean startsNestedCallee(@NotNull PsiElement position, @NotNull CallGroup callGroup) {
        return startsNestedCallee(position, callGroup, position.getTextRange().getStartOffset());
    }

    private static boolean startsNestedCallee(
            @NotNull PsiElement position,
            @NotNull CallGroup callGroup,
            int offset
    ) {
        int openParen = openParenOffset(callGroup);
        if (openParen < 0) {
            return false;
        }
        String fileText = callGroup.getContainingFile().getText();
        int index = offset;
        if (index <= openParen) {
            index = openParen + 1;
            while (index < fileText.length() && Character.isWhitespace(fileText.charAt(index))) {
                index++;
            }
        }
        while (index < fileText.length() && Character.isJavaIdentifierPart(fileText.charAt(index))) {
            index++;
        }
        return index < fileText.length() && fileText.charAt(index) == '(';
    }

    public static @NotNull Set<String> usedNamedArguments(@NotNull CallGroup callGroup) {
        String text = callGroup.getText();
        int open = text.indexOf('(');
        int close = text.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return Set.of();
        }
        Matcher matcher = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*:").matcher(text.substring(open + 1, close));
        Set<String> used = new LinkedHashSet<>();
        while (matcher.find()) {
            used.add(matcher.group(1));
        }
        return used;
    }

    public static int openParenOffset(@NotNull CallGroup callGroup) {
        String text = callGroup.getText();
        int relative = text.indexOf('(');
        return relative >= 0 ? callGroup.getTextRange().getStartOffset() + relative : -1;
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
