package dev.affogato.compiler.internal;

/**
 * Maps expression AST nodes to caret spans within the expression source slice.
 */
final class AstSpans {
    private AstSpans() {
    }

    static int startOffset(AstExpression expression) {
        if (expression instanceof BinaryExpression binary) {
            String right = binary.right().source();
            int index = binary.source().lastIndexOf(right);
            if (index >= 0) {
                return index;
            }
            int operatorIndex = topLevelOperatorIndex(binary.source(), binary.operator());
            if (operatorIndex >= 0) {
                return operatorIndex;
            }
        }
        if (expression instanceof UnaryExpression unary) {
            String inner = unary.expression().source();
            int index = unary.source().lastIndexOf(inner);
            if (index >= 0) {
                return index;
            }
        }
        if (expression instanceof IdentifierExpression identifier) {
            int index = expression.source().lastIndexOf(identifier.name());
            if (index >= 0) {
                return index;
            }
        }
        return 0;
    }

    static int spanLength(AstExpression expression, int startOffset) {
        if (expression instanceof BinaryExpression binary) {
            String right = binary.right().source();
            int index = binary.source().lastIndexOf(right);
            if (index == startOffset && !right.isEmpty()) {
                return right.length();
            }
            return Math.max(1, binary.operator().length());
        }
        if (expression instanceof UnaryExpression unary) {
            String inner = unary.expression().source();
            int index = unary.source().lastIndexOf(inner);
            if (index == startOffset && !inner.isEmpty()) {
                return inner.length();
            }
        }
        if (expression instanceof IdentifierExpression identifier) {
            return Math.max(1, identifier.name().length());
        }
        return Math.max(1, expression.source().length() - startOffset);
    }

    private static int topLevelOperatorIndex(String value, String operator) {
        int depthParen = 0;
        int depthBracket = 0;
        int depthAngle = 0;
        boolean inString = false;
        for (int index = 0; index <= value.length() - operator.length(); index++) {
            char c = value.charAt(index);
            if (inString) {
                if (c == '\\') {
                    index++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '(') {
                depthParen++;
            } else if (c == ')') {
                depthParen--;
            } else if (c == '[') {
                depthBracket++;
            } else if (c == ']') {
                depthBracket--;
            } else if (c == '<') {
                depthAngle++;
            } else if (c == '>') {
                depthAngle--;
            } else if (depthParen == 0 && depthBracket == 0 && depthAngle == 0 && value.startsWith(operator, index)) {
                return index;
            }
        }
        return -1;
    }
}
