package dev.affogato.compiler.internal;

import dev.affogato.compiler.parser.AffogatoLexer;
import dev.affogato.compiler.parser.AffogatoParser;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
        if (value.contains("?.")) {
            return new UnsupportedExpression(value, "AFFOGATO_UNSUPPORTED_SAFE_CALL", "Safe-call expressions are not in the production subset; use an explicit null check.");
        }
        if (value.contains("?:")) {
            return new UnsupportedExpression(value, "AFFOGATO_UNSUPPORTED_ELVIS", "Elvis expressions are not in the production subset; use a ternary expression.");
        }
        if (value.contains("!!")) {
            return new UnsupportedExpression(value, "AFFOGATO_UNSUPPORTED_NOT_NULL_ASSERTION", "Not-null assertion expressions are not in the production subset; use an explicit cast or null check.");
        }

        // Prefer the real ANTLR parser: it handles operator precedence, nested generics, string
        // literals containing operators and other corner cases that the regex path below mishandles.
        // Fall back to the regex path only when the string is not a clean Affogato expression
        // (for example an already-transformed Java fragment), so behavior can never regress.
        AstExpression parsed = parseViaAntlr(value);
        return parsed != null ? parsed : parseViaRegex(value);
    }

    private AstExpression parseViaRegex(String value) {
        int arrowIndex = support.topLevelOperatorIndex(value, List.of("->"));
        if (arrowIndex >= 0) {
            int arity = support.lambdaParameterArity(value.substring(0, arrowIndex));
            return new LambdaExpression(value, arity, TypeGuess.lambda(arity));
        }
        if (support.containsTopLevelMethodReference(value)) {
            return new MethodReferenceExpression(value, TypeGuess.lambda());
        }
        if (value.startsWith("switch ") || value.startsWith("switch(")) {
            return new SwitchExpressionNode(value, TypeGuess.unknown());
        }
        if (value.equals("null")) {
            return new LiteralExpression(value, TypeGuess.nullLiteral());
        }
        if (value.startsWith("\"") && support.stringLiteralEnd(value, 0) == value.length()) {
            return new LiteralExpression(value, TypeGuess.of("String"));
        }
        if (value.equals("true") || value.equals("false")) {
            return new LiteralExpression(value, TypeGuess.of("boolean"));
        }
        if (value.matches("-?\\d+[lL]")) {
            return new LiteralExpression(value, TypeGuess.of("long"));
        }
        if (value.matches("-?\\d+")) {
            return new LiteralExpression(value, TypeGuess.of("int"));
        }
        if (value.matches("-?\\d+\\.\\d+[fF]")) {
            return new LiteralExpression(value, TypeGuess.of("float"));
        }
        if (value.matches("-?\\d+\\.\\d+[dD]?")) {
            return new LiteralExpression(value, TypeGuess.of("double"));
        }
        if (value.startsWith("[") && value.endsWith("]")) {
            String contents = value.substring(1, value.length() - 1);
            List<AstExpression> elements = contents.isBlank()
                    ? List.of()
                    : support.splitTopLevel(contents, ',').stream()
                            .map(this::parse)
                            .toList();
            return new ArrayLiteralExpression(value, elements, arrayLiteralType(elements));
        }
        int arrayOpen = topLevelArrayAccessOpen(value);
        if (arrayOpen > 0) {
            AstExpression receiver = parse(value.substring(0, arrayOpen));
            AstExpression index = parse(value.substring(arrayOpen + 1, value.length() - 1));
            return new ArrayAccessExpression(value, receiver, index, arrayElementType(receiver.resolvedType()));
        }

        Matcher affogatoCast = Pattern.compile("^(.+)\\s+as\\s+([A-Za-z_][A-Za-z0-9_.$]*(?:<[^>]+>)?\\??)$").matcher(value);
        if (affogatoCast.matches()) {
            String targetType = support.stripNullableSuffix(affogatoCast.group(2));
            return new CastExpression(value, parse(affogatoCast.group(1)), targetType, TypeGuess.of(targetType));
        }
        Matcher javaCast = Pattern.compile("^\\(\\(([^)]+)\\)\\s+(.+)\\)$").matcher(value);
        if (javaCast.matches()) {
            String targetType = support.stripNullableSuffix(javaCast.group(1).trim());
            return new CastExpression(value, parse(javaCast.group(2)), targetType, TypeGuess.of(targetType));
        }

        int assignment = support.namedArgumentEquals(value);
        if (assignment > 0 && !value.substring(0, assignment).contains(",")) {
            AstExpression target = parse(value.substring(0, assignment));
            AstExpression assigned = parse(value.substring(assignment + 1));
            return new AssignmentExpression(value, target, assigned, assigned.resolvedType());
        }

        Matcher instanceOf = Pattern.compile("^(.+)\\s+is\\s+([A-Za-z_][A-Za-z0-9_.$]*(?:<[^>]+>)?\\??)$").matcher(value);
        if (instanceOf.matches()) {
            String targetType = support.stripNullableSuffix(instanceOf.group(2));
            return new InstanceOfExpression(value, parse(instanceOf.group(1)), targetType, TypeGuess.of("boolean"));
        }

        Matcher newExpression = Pattern.compile("^new\\s+([A-Za-z_$][A-Za-z0-9_.$]*(?:<[^>]+>)?(?:\\[\\])*)\\s*\\((.*)\\)$").matcher(value);
        if (newExpression.matches()) {
            return new ConstructorExpression(value, newExpression.group(1), argumentExpressions(newExpression.group(2)), TypeGuess.of(newExpression.group(1)));
        }

        Matcher constructor = Pattern.compile("^([A-Za-z_$][A-Za-z0-9_.$]*(?:<[^>]+>)?)\\s*\\((.*)\\)$").matcher(value);
        if (constructor.matches()) {
            String typeName = constructor.group(1);
            String simpleName = support.simpleTypeName(typeName);
            if (!simpleName.isBlank() && Character.isUpperCase(simpleName.charAt(0))) {
                return new ConstructorExpression(value, typeName, argumentExpressions(constructor.group(2)), TypeGuess.of(support.constructorImplementation(typeName)));
            }
        }

        int callOpen = support.callOpenParen(value);
        if (callOpen > 0) {
            String callName = support.callNameBefore(value, callOpen);
            List<AstExpression> arguments = argumentExpressions(value.substring(callOpen + 1, value.length() - 1));
            if (!callName.isBlank()) {
                String simple = support.simpleTypeName(callName);
                if (!simple.isBlank() && Character.isUpperCase(simple.charAt(0)) && !callName.contains(".")) {
                    return new ConstructorExpression(value, callName, arguments, TypeGuess.of(support.constructorImplementation(callName)));
                }
                AstExpression receiver = new UnknownExpression("");
                int dot = callName.lastIndexOf('.');
                if (dot > 0) {
                    receiver = parse(callName.substring(0, dot));
                }
                return new CallExpression(value, callName, receiver, arguments, TypeGuess.unknown());
            }
        }

        int ternaryQ = support.topLevelOperatorIndex(value, List.of("?"));
        if (ternaryQ > 0 && !Character.isJavaIdentifierPart(value.charAt(ternaryQ - 1))) {
            String rest = value.substring(ternaryQ + 1).trim();
            int colonIdx = support.topLevelOperatorIndex(rest, List.of(":"));
            if (colonIdx >= 0) {
                AstExpression thenExpr = parse(rest.substring(0, colonIdx));
                AstExpression elseExpr = parse(rest.substring(colonIdx + 1));
                TypeGuess type = ternaryType(thenExpr.resolvedType(), elseExpr.resolvedType());
                return new TernaryExpression(value, parse(value.substring(0, ternaryQ)), thenExpr, elseExpr, type.isKnown() ? type : TypeGuess.unknown());
            }
        }

        for (String operator : List.of("||", "&&", "==", "!=", "<=", ">=", "<", ">")) {
            int index = support.topLevelOperatorIndex(value, List.of(operator));
            if (index > 0) {
                return new BinaryExpression(value, operator, parse(value.substring(0, index)), parse(value.substring(index + operator.length())), TypeGuess.of("boolean"));
            }
        }
        for (String operator : List.of("+", "-", "*", "/", "%")) {
            int index = support.topLevelOperatorIndex(value, List.of(operator));
            if (index > 0) {
                AstExpression left = parse(value.substring(0, index));
                AstExpression right = parse(value.substring(index + operator.length()));
                TypeGuess type = numericOrStringType(operator, left.resolvedType(), right.resolvedType());
                return new BinaryExpression(value, operator, left, right, type);
            }
        }
        if (support.startsWithBooleanNegation(value)) {
            String operand = value.startsWith("not(") && value.endsWith(")")
                    ? value.substring("not(".length(), value.length() - 1)
                    : value.substring(1);
            return new UnaryExpression(value, "!", parse(operand), TypeGuess.of("boolean"));
        }

        int dot = value.lastIndexOf('.');
        if (dot > 0 && dot < value.length() - 1 && value.indexOf('(') < 0) {
            AstExpression receiver = parse(value.substring(0, dot));
            String property = value.substring(dot + 1);
            return new PropertyAccessExpression(value, receiver, property, TypeGuess.unknown());
        }

        String variableType = support.variableType(value);
        if (variableType != null) {
            return new IdentifierExpression(value, value, TypeGuess.of(variableType));
        }
        if (value.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
            return new IdentifierExpression(value, value, TypeGuess.unknown());
        }
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
            return new AssignmentExpression(src(ctx, whole), target, value, value.resolvedType());
        }
        return buildTernary(ctx.ternaryExpression(), whole);
    }

    private AstExpression buildTernary(AffogatoParser.TernaryExpressionContext ctx, String whole) {
        if (ctx.QUESTION() != null) {
            AstExpression condition = buildLogicalOr(ctx.logicalOrExpression(), whole);
            AstExpression thenExpression = buildExpression(ctx.expression(0), whole);
            AstExpression elseExpression = buildExpression(ctx.expression(1), whole);
            TypeGuess type = ternaryType(thenExpression.resolvedType(), elseExpression.resolvedType());
            return new TernaryExpression(src(ctx, whole), condition, thenExpression, elseExpression,
                    type.isKnown() ? type : TypeGuess.unknown());
        }
        return buildLogicalOr(ctx.logicalOrExpression(), whole);
    }

    private AstExpression buildLogicalOr(AffogatoParser.LogicalOrExpressionContext ctx, String whole) {
        return foldBinary(ctx, whole, child -> buildLogicalAnd((AffogatoParser.LogicalAndExpressionContext) child, whole), true);
    }

    private AstExpression buildLogicalAnd(AffogatoParser.LogicalAndExpressionContext ctx, String whole) {
        return foldBinary(ctx, whole, child -> buildEquality((AffogatoParser.EqualityExpressionContext) child, whole), true);
    }

    private AstExpression buildEquality(AffogatoParser.EqualityExpressionContext ctx, String whole) {
        return foldBinary(ctx, whole, child -> buildRelational((AffogatoParser.RelationalExpressionContext) child, whole), true);
    }

    private AstExpression buildRelational(AffogatoParser.RelationalExpressionContext ctx, String whole) {
        if (!ctx.IS().isEmpty()) {
            String target = support.stripNullableSuffix(ctx.typeRef(0).getText());
            return new InstanceOfExpression(src(ctx, whole), buildCast(ctx.castExpression(0), whole), target, TypeGuess.of("boolean"));
        }
        return foldBinary(ctx, whole, child -> buildCast((AffogatoParser.CastExpressionContext) child, whole), true);
    }

    private AstExpression buildCast(AffogatoParser.CastExpressionContext ctx, String whole) {
        if (ctx.AS() != null) {
            String target = support.stripNullableSuffix(ctx.typeRef().getText());
            return new CastExpression(src(ctx, whole), buildAdditive(ctx.additiveExpression(), whole), target, TypeGuess.of(target));
        }
        return buildAdditive(ctx.additiveExpression(), whole);
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
        return buildPostfix(ctx.postfixExpression(), whole);
    }

    private AstExpression buildPostfix(AffogatoParser.PostfixExpressionContext ctx, String whole) {
        AstExpression current = buildPrimary(ctx.primaryExpression(), whole);
        List<AffogatoParser.PostfixPartContext> parts = ctx.postfixPart();
        int index = 0;
        while (index < parts.size()) {
            AffogatoParser.PostfixPartContext part = parts.get(index);
            if (part.LPAREN() != null) {
                // A call applied directly to the primary, e.g. `foo(args)` or `Foo(args)`.
                List<AstExpression> arguments = buildArguments(part.argumentList(), whole);
                current = makeCall(current.source(), new UnknownExpression(""), arguments, srcBetween(whole, ctx, part));
                index++;
            } else {
                String name = part.Identifier().getText();
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
            // Named arguments (`name = value`) and positional arguments both contribute their value.
            arguments.add(buildExpression(argument.expression(), whole));
        }
        return arguments;
    }

    private List<AstExpression> argumentExpressions(String args) {
        if (args.isBlank()) {
            return List.of();
        }
        List<AstExpression> expressions = new ArrayList<>();
        for (String part : support.splitTopLevel(args, ',')) {
            int equals = support.namedArgumentEquals(part);
            expressions.add(parse(equals > 0 ? part.substring(equals + 1) : part));
        }
        return expressions;
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

    private int topLevelArrayAccessOpen(String value) {
        if (!value.endsWith("]")) {
            return -1;
        }
        int depth = 0;
        boolean inString = false;
        for (int index = value.length() - 1; index >= 0; index--) {
            char current = value.charAt(index);
            char previous = index > 0 ? value.charAt(index - 1) : '\0';
            if (current == '"' && previous != '\\') {
                inString = !inString;
            }
            if (inString) {
                continue;
            }
            if (current == ']') {
                depth++;
            } else if (current == '[') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }
}
