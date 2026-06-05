package dev.affogato.compiler.internal;

import dev.affogato.compiler.parser.AffogatoLexer;
import dev.affogato.compiler.parser.AffogatoParser;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

final class ExpressionSemanticChecker {
    interface Support {
        String stripOuterParens(String text);

        boolean containsTopLevelMethodReference(String value);

        int topLevelOperatorIndex(String value, List<String> operators);

        int lambdaParameterArity(String header);

        int stringLiteralEnd(String expression, int openQuoteIndex);

        String stripNullableSuffix(String typeName);

        int namedArgumentEquals(String expression);

        int callOpenParen(String value);

        String callNameBefore(String expression, int openIndex);

        String simpleTypeName(String type);

        String constructorImplementation(String typeName);

        List<String> splitTopLevel(String text, char delimiter);

        boolean startsWithBooleanNegation(String value);

        boolean isStringType(TypeGuess type);

        boolean isNumericType(TypeGuess type);

        String promotedNumericType(String left, String right);

        String variableType(String name);
    }

    private final Support support;

    ExpressionSemanticChecker(Support support) {
        this.support = support;
    }

    AstExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            return new UnknownExpression("");
        }
        String value = support.stripOuterParens(expression.trim());

        // Try ANTLR first. A successful parse means the expression is syntactically valid
        // Affogato — ?. / ?: / !! can only appear inside a string literal or comment and are
        // not actual unsupported operators.  Only flag them as unsupported when ANTLR also
        // fails, which is the case for actual usage like `x?.method()`.
        AstExpression parsed = parseViaAntlr(value);
        if (parsed != null) {
            return parsed;
        }
        if (value.contains("?.")) {
            return new UnsupportedExpression(value, "AFFOGATO_UNSUPPORTED_SAFE_CALL", "Safe-call expressions are not in the production subset; use an explicit null check.");
        }
        if (value.contains("?:")) {
            return new UnsupportedExpression(value, "AFFOGATO_UNSUPPORTED_ELVIS", "Elvis expressions are not in the production subset; use a ternary expression.");
        }
        if (value.contains("!!")) {
            return new UnsupportedExpression(value, "AFFOGATO_UNSUPPORTED_NOT_NULL_ASSERTION", "Not-null assertion expressions are not in the production subset; use an explicit cast or null check.");
        }
        // No regex fallback: unknown nodes skip AST-based checks while string transforms continue
        // (e.g. trailing-closure merge embeds statement-shaped Java in a call argument).
        return new UnknownExpression(value);
    }

    // ── ANTLR-backed expression AST construction ─────────────────────────────────
    //
    // Walks the real Affogato `expression` parse tree to build the same AstExpression records the
    // regex path produces, but with correct precedence and nesting. `whole` is the exact string that
    // was handed to the parser, so token start/stop indices slice back into it to recover each node's
    // original source text (preserving spacing that downstream string consumers rely on).

    private AstExpression parseViaAntlr(String value) {
        try {
            AffogatoLexer lexer = new AffogatoLexer(CharStreams.fromString(value));
            SyntaxFlag flag = new SyntaxFlag();
            lexer.removeErrorListeners();
            lexer.addErrorListener(flag);
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            AffogatoParser parser = new AffogatoParser(tokens);
            parser.removeErrorListeners();
            parser.addErrorListener(flag);
            AffogatoParser.ExpressionContext tree = parser.expression();
            if (flag.errors || parser.getCurrentToken().getType() != Token.EOF) {
                return null;
            }
            return buildExpression(tree, value);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static final class SyntaxFlag extends BaseErrorListener {
        private boolean errors;

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String message, RecognitionException exception) {
            errors = true;
        }
    }

    private static String src(ParserRuleContext ctx, String whole) {
        return whole.substring(ctx.getStart().getStartIndex(), ctx.getStop().getStopIndex() + 1);
    }

    private static String srcBetween(String whole, ParserRuleContext first, ParserRuleContext last) {
        return whole.substring(first.getStart().getStartIndex(), last.getStop().getStopIndex() + 1);
    }

    private AstExpression buildExpression(AffogatoParser.ExpressionContext ctx, String whole) {
        return buildLambda(ctx.lambdaExpression(), whole);
    }

    private AstExpression buildLambda(AffogatoParser.LambdaExpressionContext ctx, String whole) {
        if (ctx.ARROW() != null) {
            int arity = support.lambdaParameterArity(src(ctx.lambdaParameters(), whole));
            return new LambdaExpression(src(ctx, whole), arity, TypeGuess.lambda(arity));
        }
        if (ctx.methodReferenceExpression() != null) {
            return new MethodReferenceExpression(src(ctx, whole), TypeGuess.lambda());
        }
        return buildAssignment(ctx.assignmentExpression(), whole);
    }

    private AstExpression buildAssignment(AffogatoParser.AssignmentExpressionContext ctx, String whole) {
        if (ctx.assignmentExpression() != null) {
            AstExpression target = buildTernary(ctx.ternaryExpression(), whole);
            AstExpression value = buildAssignment(ctx.assignmentExpression(), whole);
            return new AssignmentExpression(src(ctx, whole), ctx.getChild(1).getText(), target, value, value.resolvedType());
        }
        return buildTernary(ctx.ternaryExpression(), whole);
    }

    private AstExpression buildTernary(AffogatoParser.TernaryExpressionContext ctx, String whole) {
        if (ctx.switchExpression() != null) {
            return new SwitchExpressionNode(src(ctx.switchExpression(), whole), TypeGuess.unknown());
        }
        if (ctx.QUESTION() != null) {
            AstExpression condition = buildExpression(ctx.getChild(0).getText(), null); // Placeholder for logicalOr
            AstExpression thenExpression = buildExpression(ctx.expression(0), whole);
            AstExpression elseExpression = buildExpression(ctx.expression(1), whole);
            TypeGuess type = ternaryType(thenExpression.resolvedType(), elseExpression.resolvedType());
            return new TernaryExpression(src(ctx, whole), condition, thenExpression, elseExpression,
                    type.isKnown() ? type : TypeGuess.unknown());
        }
        return new UnknownExpression(src(ctx, whole)); // Temporary
    }

    private AstExpression buildLogicalOr(AffogatoParser.LogicalOrExpressionContext ctx, String whole) {
        return foldBinary(ctx, whole, child -> buildLogicalAnd((AffogatoParser.LogicalAndExpressionContext) child, whole), true);
    }

    private AstExpression buildLogicalAnd(AffogatoParser.LogicalAndExpressionContext ctx, String whole) {
        return foldBinary(ctx, whole, child -> buildBitwiseOr((AffogatoParser.BitwiseOrExpressionContext) child, whole), true);
    }

    private AstExpression buildBitwiseOr(AffogatoParser.BitwiseOrExpressionContext ctx, String whole) {
        return foldBinary(ctx, whole, child -> buildBitwiseXor((AffogatoParser.BitwiseXorExpressionContext) child, whole), false);
    }

    private AstExpression buildBitwiseXor(AffogatoParser.BitwiseXorExpressionContext ctx, String whole) {
        return foldBinary(ctx, whole, child -> buildBitwiseAnd((AffogatoParser.BitwiseAndExpressionContext) child, whole), false);
    }

    private AstExpression buildBitwiseAnd(AffogatoParser.BitwiseAndExpressionContext ctx, String whole) {
        return foldBinary(ctx, whole, child -> buildEquality((AffogatoParser.EqualityExpressionContext) child, whole), false);
    }

    private AstExpression buildEquality(AffogatoParser.EqualityExpressionContext ctx, String whole) {
        return foldBinary(ctx, whole, child -> buildRelational((AffogatoParser.RelationalExpressionContext) child, whole), true);
    }

    private AstExpression buildRelational(AffogatoParser.RelationalExpressionContext ctx, String whole) {
        if (!ctx.IS().isEmpty()) {
            String target = support.stripNullableSuffix(ctx.typeRef(0).getText());
            return new InstanceOfExpression(src(ctx, whole), buildShift(ctx.shiftExpression(0), whole), target, TypeGuess.of("boolean"));
        }
        return foldBinary(ctx, whole, child -> buildShift((AffogatoParser.ShiftExpressionContext) child, whole), true);
    }

    private AstExpression buildShift(AffogatoParser.ShiftExpressionContext ctx, String whole) {
        List<AffogatoParser.CastExpressionContext> operands = ctx.castExpression();
        AstExpression current = buildCast(operands.get(0), whole);
        List<AffogatoParser.ShiftOpContext> ops = ctx.shiftOp();
        for (int index = 0; index < ops.size(); index++) {
            AffogatoParser.CastExpressionContext rightCtx = operands.get(index + 1);
            AstExpression right = buildCast(rightCtx, whole);
            String operator = ops.get(index).getText();
            TypeGuess type = support.isNumericType(current.resolvedType()) && support.isNumericType(right.resolvedType())
                    ? current.resolvedType()
                    : TypeGuess.unknown();
            current = new BinaryExpression(srcBetween(whole, operands.get(0), rightCtx), operator, current, right, type);
        }
        return current;
    }

    private AstExpression buildCast(AffogatoParser.CastExpressionContext ctx, String whole) {
        AstExpression current = buildAdditive(ctx.additiveExpression(), whole);
        // `expr as A as B` chains left-to-right into nested casts: ((B)((A) expr)).
        for (AffogatoParser.TypeRefContext typeRef : ctx.typeRef()) {
            String target = support.stripNullableSuffix(typeRef.getText());
            current = new CastExpression(srcBetween(whole, ctx.additiveExpression(), typeRef), current, target, TypeGuess.of(target));
        }
        return current;
    }

    private AstExpression buildAdditive(AffogatoParser.AdditiveExpressionContext ctx, String whole) {
        return foldBinary(ctx, whole, child -> buildMultiplicative((AffogatoParser.MultiplicativeExpressionContext) child, whole), false);
    }

    private AstExpression buildMultiplicative(AffogatoParser.MultiplicativeExpressionContext ctx, String whole) {
        return foldBinary(ctx, whole, child -> buildUnary((AffogatoParser.UnaryExpressionContext) child, whole), false);
    }

    /**
     * Folds a left-associative binary rule ({@code operand (OP operand)*}) into nested
     * BinaryExpression nodes. With a single operand it returns that operand untouched.
     * {@code booleanResult} selects boolean-valued operators (logical, equality, relational) from
     * arithmetic operators, which take {@link #numericOrStringType}.
     */
    private AstExpression foldBinary(ParserRuleContext ctx, String whole, Function<ParseTree, AstExpression> build, boolean booleanResult) {
        AstExpression current = null;
        ParserRuleContext first = null;
        String operator = null;
        for (int index = 0; index < ctx.getChildCount(); index++) {
            ParseTree child = ctx.getChild(index);
            if (child instanceof TerminalNode terminal) {
                operator = terminal.getText();
                continue;
            }
            ParserRuleContext operandCtx = (ParserRuleContext) child;
            AstExpression operand = build.apply(child);
            if (current == null) {
                current = operand;
                first = operandCtx;
            } else {
                TypeGuess type = booleanResult
                        ? TypeGuess.of("boolean")
                        : numericOrStringType(operator, current.resolvedType(), operand.resolvedType());
                current = new BinaryExpression(srcBetween(whole, first, operandCtx), operator, current, operand, type);
            }
        }
        return current;
    }

    private AstExpression buildUnary(AffogatoParser.UnaryExpressionContext ctx, String whole) {
        if (ctx.NOT() != null) {
            return new UnaryExpression(src(ctx, whole), "!", buildExpression(ctx.expression(), whole), TypeGuess.of("boolean"));
        }
        if (ctx.BANG() != null) {
            return new UnaryExpression(src(ctx, whole), "!", buildUnary(ctx.unaryExpression(), whole), TypeGuess.of("boolean"));
        }
        if (ctx.MINUS() != null) {
            AstExpression operand = buildUnary(ctx.unaryExpression(), whole);
            TypeGuess type = support.isNumericType(operand.resolvedType()) ? operand.resolvedType() : TypeGuess.unknown();
            return new UnaryExpression(src(ctx, whole), "-", operand, type);
        }
        if (ctx.TILDE() != null) {
            AstExpression operand = buildUnary(ctx.unaryExpression(), whole);
            TypeGuess type = support.isNumericType(operand.resolvedType()) ? operand.resolvedType() : TypeGuess.unknown();
            return new UnaryExpression(src(ctx, whole), "~", operand, type);
        }
        if (ctx.PLUS_PLUS() != null) {
            AstExpression operand = buildUnary(ctx.unaryExpression(), whole);
            TypeGuess type = support.isNumericType(operand.resolvedType()) ? operand.resolvedType() : TypeGuess.unknown();
            return new UnaryExpression(src(ctx, whole), "++", operand, type);
        }
        if (ctx.MINUS_MINUS() != null) {
            AstExpression operand = buildUnary(ctx.unaryExpression(), whole);
            TypeGuess type = support.isNumericType(operand.resolvedType()) ? operand.resolvedType() : TypeGuess.unknown();
            return new UnaryExpression(src(ctx, whole), "--", operand, type);
        }
        return buildPostfix(ctx.postfixExpression(), whole);
    }

    private AstExpression buildPostfix(AffogatoParser.PostfixExpressionContext ctx, String whole) {
        AstExpression current = buildPrimary(ctx.primaryExpression(), whole);
        List<AffogatoParser.PostfixPartContext> parts = ctx.postfixPart();
        int index = 0;
        while (index < parts.size()) {
            AffogatoParser.PostfixPartContext part = parts.get(index);
            if (part.PLUS_PLUS() != null) {
                TypeGuess type = support.isNumericType(current.resolvedType()) ? current.resolvedType() : TypeGuess.unknown();
                current = new UnaryExpression(srcBetween(whole, ctx, part), "++", current, type);
                index++;
            } else if (part.MINUS_MINUS() != null) {
                TypeGuess type = support.isNumericType(current.resolvedType()) ? current.resolvedType() : TypeGuess.unknown();
                current = new UnaryExpression(srcBetween(whole, ctx, part), "--", current, type);
                index++;
            } else if (part.LBRACK() != null) {
                AstExpression indexExpr = buildExpression(part.expression(), whole);
                current = new ArrayAccessExpression(
                        srcBetween(whole, ctx, part),
                        current,
                        indexExpr,
                        arrayElementType(current.resolvedType()));
                index++;
            } else if (part.LPAREN() != null) {
                // A call applied directly to the primary, e.g. `foo(args)` or `Foo(args)`.
                List<AstExpression> arguments = buildArguments(part.argumentList(), whole);
                current = makeCall(current.source(), new UnknownExpression(""), arguments, srcBetween(whole, ctx, part));
                index++;
            } else {
                String name = part.Identifier() != null ? part.Identifier().getText() : part.IN().getText();
                if (index + 1 < parts.size() && parts.get(index + 1).LPAREN() != null) {
                    // `receiver.name(args)` — a method (or qualified constructor) call.
                    AffogatoParser.PostfixPartContext callPart = parts.get(index + 1);
                    List<AstExpression> arguments = buildArguments(callPart.argumentList(), whole);
                    String callName = current.source() + "." + name;
                    current = makeCall(callName, current, arguments, srcBetween(whole, ctx, callPart));
                    index += 2;
                } else {
                    current = new PropertyAccessExpression(srcBetween(whole, ctx, part), current, name, TypeGuess.unknown());
                    index++;
                }
            }
        }
        return current;
    }

    /**
     * Builds a call site, mirroring the regex path's heuristic: a callable whose simple (last) name
     * segment starts with an uppercase letter is treated as a constructor, otherwise as a method call.
     */
    private AstExpression makeCall(String callName, AstExpression receiver, List<AstExpression> arguments, String source) {
        String simple = support.simpleTypeName(callName);
        if (!simple.isBlank() && Character.isUpperCase(simple.charAt(0))) {
            return new ConstructorExpression(source, callName, arguments, TypeGuess.of(support.constructorImplementation(callName)));
        }
        return new CallExpression(source, callName, receiver, arguments, TypeGuess.unknown());
    }

    private AstExpression buildPrimary(AffogatoParser.PrimaryExpressionContext ctx, String whole) {
        if (ctx.literal() != null) {
            return buildLiteral(ctx.literal(), whole);
        }
        if (ctx.CLASS() != null) {
            return new ClassLiteralExpression(src(ctx, whole), support.stripNullableSuffix(ctx.typeRef().getText()), TypeGuess.of("java.lang.Class"));
        }
        if (ctx.genericConstructorExpression() != null) {
            AffogatoParser.GenericConstructorExpressionContext generic = ctx.genericConstructorExpression();
            String typeName = generic.qualifiedName().getText() + generic.typeArguments().getText();
            return new ConstructorExpression(src(ctx, whole), typeName, buildArguments(generic.argumentList(), whole),
                    TypeGuess.of(support.constructorImplementation(typeName)));
        }
        if (ctx.NEW() != null) {
            String typeName = ctx.typeRef().getText();
            return new ConstructorExpression(src(ctx, whole), typeName, buildArguments(ctx.argumentList(), whole), TypeGuess.of(typeName));
        }
        if (ctx.arrayLiteral() != null) {
            List<AstExpression> elements = new ArrayList<>();
            for (AffogatoParser.ExpressionContext element : ctx.arrayLiteral().expression()) {
                elements.add(buildExpression(element, whole));
            }
            return new ArrayLiteralExpression(src(ctx, whole), elements, arrayLiteralType(elements));
        }
        if (ctx.expression() != null) {
            // Parenthesized expression: drop the parentheses, exactly like stripOuterParens.
            return buildExpression(ctx.expression(), whole);
        }
        String text = src(ctx, whole);
        String variableType = support.variableType(text);
        if (variableType != null) {
            return new IdentifierExpression(text, text, TypeGuess.of(variableType));
        }
        return new IdentifierExpression(text, text, TypeGuess.unknown());
    }

    private AstExpression buildLiteral(AffogatoParser.LiteralContext ctx, String whole) {
        String text = src(ctx, whole);
        if (ctx.NULL() != null) {
            return new LiteralExpression(text, TypeGuess.nullLiteral());
        }
        if (ctx.StringLiteral() != null) {
            return new LiteralExpression(text, TypeGuess.of("String"));
        }
        if (ctx.TRUE() != null || ctx.FALSE() != null) {
            return new LiteralExpression(text, TypeGuess.of("boolean"));
        }
        if (ctx.IntegerLiteral() != null) {
            boolean isLong = text.endsWith("l") || text.endsWith("L");
            return new LiteralExpression(text, TypeGuess.of(isLong ? "long" : "int"));
        }
        if (ctx.FloatingPointLiteral() != null) {
            boolean isFloat = text.endsWith("f") || text.endsWith("F");
            return new LiteralExpression(text, TypeGuess.of(isFloat ? "float" : "double"));
        }
        return new LiteralExpression(text, TypeGuess.unknown());
    }

    private List<AstExpression> buildArguments(AffogatoParser.ArgumentListContext ctx, String whole) {
        if (ctx == null) {
            return List.of();
        }
        List<AstExpression> arguments = new ArrayList<>();
        for (AffogatoParser.ArgumentContext argument : ctx.argument()) {
            AstExpression expr = buildExpression(argument.expression(), whole);
            if (argument.Identifier() != null) {
                arguments.add(new NamedArgumentExpression(argument.Identifier().getText(), expr, expr.resolvedType()));
            } else {
                arguments.add(expr);
            }
        }
        return arguments;
    }

    private TypeGuess numericOrStringType(String operator, TypeGuess left, TypeGuess right) {
        if (operator.equals("+") && (support.isStringType(left) || support.isStringType(right))) {
            return TypeGuess.of("String");
        }
        if (support.isNumericType(left) && support.isNumericType(right)) {
            return TypeGuess.of(support.promotedNumericType(left.javaType(), right.javaType()));
        }
        return TypeGuess.unknown();
    }

    private TypeGuess ternaryType(TypeGuess thenType, TypeGuess elseType) {
        if (thenType.isNullLiteral()) {
            return elseType;
        }
        if (elseType.isNullLiteral()) {
            return thenType;
        }
        if (!thenType.isKnown() || !elseType.isKnown()) {
            return TypeGuess.unknown();
        }
        if (thenType.javaType().equals(elseType.javaType())) {
            return thenType;
        }
        if (support.isNumericType(thenType) && support.isNumericType(elseType)) {
            return TypeGuess.of(support.promotedNumericType(thenType.javaType(), elseType.javaType()));
        }
        return TypeGuess.unknown();
    }

    private TypeGuess arrayLiteralType(List<AstExpression> elements) {
        if (elements.isEmpty()) {
            return TypeGuess.of("Object[]");
        }
        TypeGuess current = null;
        for (AstExpression element : elements) {
            TypeGuess type = element.resolvedType();
            if (!type.isKnown() || type.isNullLiteral() || type.isLambda()) {
                return TypeGuess.unknown();
            }
            if (current == null) {
                current = type;
                continue;
            }
            if (current.javaType().equals(type.javaType())) {
                continue;
            }
            if (support.isNumericType(current) && support.isNumericType(type)) {
                current = TypeGuess.of(support.promotedNumericType(current.javaType(), type.javaType()));
                continue;
            }
            return TypeGuess.of("Object[]");
        }
        return current == null ? TypeGuess.of("Object[]") : TypeGuess.of(current.javaType() + "[]");
    }

    private TypeGuess arrayElementType(TypeGuess receiverType) {
        if (!receiverType.isKnown() || receiverType.isNullLiteral() || !receiverType.javaType().endsWith("[]")) {
            return TypeGuess.unknown();
        }
        return TypeGuess.of(receiverType.javaType().substring(0, receiverType.javaType().length() - 2));
    }
}
