package dev.affogato.compiler.internal;

import dev.affogato.compiler.parser.AffogatoLexer;
import dev.affogato.compiler.parser.AffogatoParser;
import dev.affogato.compiler.internal.TranspilerTypes.*;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class ExpressionRenderer {
    private final ExpressionRenderServices services;

    ExpressionRenderer(ExpressionRenderServices services) {
        this.services = services;
    }

    String render(AstExpression ast, MethodContext context) {
        if (ast instanceof LiteralExpression literal) {
            String source = literal.source();
            if (source.startsWith("\"")) {
                return services.transformStringInterpolation(source, context);
            }
            return source;
        }
        if (ast instanceof ArrayLiteralExpression arrayLiteral) {
            if (arrayLiteral.resolvedType().isKnown() && arrayLiteral.resolvedType().javaType().endsWith("[]")) {
                String arrayType = formatGenericCommas(arrayLiteral.resolvedType().javaType());
                List<String> innerRendered = new ArrayList<>();
                for (AstExpression el : arrayLiteral.elements()) {
                    if (el instanceof ArrayLiteralExpression nested) {
                        innerRendered.add(renderNestedArrayInitializer(nested, context));
                    } else {
                        innerRendered.add(render(el, context));
                    }
                }
                return "new " + arrayType + "{" + String.join(", ", innerRendered) + "}";
            }
            List<String> renderedElements = new ArrayList<>();
            for (AstExpression el : arrayLiteral.elements()) {
                renderedElements.add(render(el, context));
            }
            String expected = services.getExpectedArrayElementType();
            String elementType = expected != null ? expected : services.inferArrayElementType(renderedElements, context);
            String savedExpected = services.getExpectedArrayElementType();
            services.setExpectedArrayElementType(null);
            List<String> innerRendered = new ArrayList<>();
            for (AstExpression el : arrayLiteral.elements()) {
                innerRendered.add(render(el, context));
            }
            services.setExpectedArrayElementType(savedExpected);
            return "new " + formatGenericCommas(elementType) + "[]{" + String.join(", ", innerRendered) + "}";
        }
        if (ast instanceof ArrayAccessExpression arrayAccess) {
            return render(arrayAccess.receiver(), context) + "[" + render(arrayAccess.index(), context) + "]";
        }
        if (ast instanceof ClassLiteralExpression classLiteral) {
            return classLiteral.typeName() + ".class";
        }
        if (ast instanceof IdentifierExpression identifier) {
            String name = identifier.name();
            if (name.equals("this") && context.receiverType != null) {
                return "$this";
            }
            if (context.receiverType != null && !name.equals("this") && !name.equals("super")
                    && !context.variableTypes.containsKey(name) && !name.equals("$this")
                    && context.receiverHasField(name)) {
                String resolvedReceiverType = context.activeTypeParams.contains(context.receiverType) ? "java.lang.Object" : context.receiverType;
                AffogatoSymbolResolver.PropertyHop hop = services.resolvePropertyHopOnType(resolvedReceiverType, name, context);
                if (hop != null) {
                    return hop.call() ? "$this." + hop.accessor() + "()" : "$this." + hop.accessor();
                }
                return "$this." + name;
            }
            return name;
        }
        if (ast instanceof CallExpression call) {
            String simpleName = call.name().contains(".") ? call.name().substring(call.name().lastIndexOf('.') + 1) : call.name();
            
            // Build arguments
            List<TypedArgument> typedArgs = new ArrayList<>();
            boolean hasNamed = false;
            for (AstExpression arg : call.arguments()) {
                if (arg instanceof NamedArgumentExpression named) {
                    hasNamed = true;
                    String valStr = render(named.expression(), context);
                    TypeGuess valType = named.expression().resolvedType().isKnown() ? named.expression().resolvedType() : services.inferExpressionType(valStr, context);
                    typedArgs.add(new TypedArgument(named.name(), valStr, valType, named.expression()));
                } else {
                    String valStr = render(arg, context);
                    TypeGuess valType = arg.resolvedType().isKnown() ? arg.resolvedType() : services.inferExpressionType(valStr, context);
                    typedArgs.add(new TypedArgument("", valStr, valType, arg));
                }
            }

            // Check if extension call
            if (call.receiver() != null && !(call.receiver() instanceof UnknownExpression)) {
                String receiverText = render(call.receiver(), context);
                TypeGuess receiverType = call.receiver().resolvedType().isKnown() ? call.receiver().resolvedType() : services.inferExpressionType(receiverText, context);
                String rawOwner = receiverType.javaType();
                String resolvedOwner = context.activeTypeParams.contains(rawOwner) ? "java.lang.Object" : rawOwner;
                Optional<ExtensionMatch> match = context.dispatchExtension(services.simpleTypeName(resolvedOwner), simpleName, typedArgs);
                if (match.isPresent()) {
                    List<String> matchedArgs = match.get().resolved().expressions();
                    return match.get().symbol().holderJavaName() + "." + simpleName + "(" + receiverText + (matchedArgs.isEmpty() ? "" : ", " + String.join(", ", matchedArgs)) + ")";
                }
            }

            // Named argument resolution for normal calls
            List<String> renderedArgs = new ArrayList<>();
            if (hasNamed) {
                Optional<ResolvedArguments> resolved = context.resolveArguments(call.name(), typedArgs);
                if (resolved.isPresent()) {
                    renderedArgs.addAll(resolved.get().expressions());
                } else {
                    for (TypedArgument ta : typedArgs) {
                        renderedArgs.add(ta.expression());
                    }
                }
            } else {
                for (TypedArgument ta : typedArgs) {
                    renderedArgs.add(ta.expression());
                }
            }

            // Built-in println
            if (simpleName.equals("println") && (call.receiver() == null || call.receiver() instanceof UnknownExpression)
                    && !context.hasCurrentMethod("println") && !context.variableTypes.containsKey("println")) {
                return "System.out.println(" + String.join(", ", renderedArgs) + ")";
            }

            // Receiver handling
            if (call.receiver() != null && !(call.receiver() instanceof UnknownExpression)) {
                return render(call.receiver(), context) + "." + simpleName + "(" + String.join(", ", renderedArgs) + ")";
            }

            // Implicit receiver
            if (context.receiverType != null && !context.variableTypes.containsKey(simpleName) && context.receiverHasMethod(simpleName)) {
                return "$this." + simpleName + "(" + String.join(", ", renderedArgs) + ")";
            }

            return call.name() + "(" + String.join(", ", renderedArgs) + ")";
        }
        if (ast instanceof ConstructorExpression constructor) {
            String impl = services.constructorImplementation(constructor.typeName());
            
            // Build arguments
            List<TypedArgument> typedArgs = new ArrayList<>();
            boolean hasNamed = false;
            for (AstExpression arg : constructor.arguments()) {
                if (arg instanceof NamedArgumentExpression named) {
                    hasNamed = true;
                    String valStr = render(named.expression(), context);
                    TypeGuess valType = named.expression().resolvedType().isKnown() ? named.expression().resolvedType() : services.inferExpressionType(valStr, context);
                    typedArgs.add(new TypedArgument(named.name(), valStr, valType, named.expression()));
                } else {
                    String valStr = render(arg, context);
                    TypeGuess valType = arg.resolvedType().isKnown() ? arg.resolvedType() : services.inferExpressionType(valStr, context);
                    typedArgs.add(new TypedArgument("", valStr, valType, arg));
                }
            }

            List<String> renderedArgs = new ArrayList<>();
            if (hasNamed) {
                ClassSymbol affogatoTarget = services.classSymbol(constructor.typeName(), context.unit);
                Optional<ResolvedArguments> resolved;
                if (affogatoTarget != null) {
                    resolved = context.resolveArguments(constructor.typeName(), typedArgs);
                } else {
                    resolved = context.javaResolver.resolveConstructorArguments(impl, typedArgs, context.unit);
                }
                if (resolved.isPresent()) {
                    renderedArgs.addAll(resolved.get().expressions());
                } else {
                    for (TypedArgument ta : typedArgs) {
                        renderedArgs.add(ta.expression());
                    }
                }
            } else {
                for (int index = 0; index < constructor.arguments().size(); index++) {
                    AstExpression arg = constructor.arguments().get(index);
                    String lastParamType = services.lastParameterType(constructor.typeName(), context);
                    String elementType = services.supplierListElementType(lastParamType);
                    if (index == constructor.arguments().size() - 1 && elementType != null) {
                        if (arg instanceof LambdaExpression lambda) {
                            renderedArgs.add(renderListBuilder(lambda, elementType, context));
                        } else {
                            renderedArgs.add(render(arg, context));
                        }
                    } else {
                        renderedArgs.add(render(arg, context));
                    }
                }
            }

            if (AffogatoTypeChecker.isArrayType(impl) && renderedArgs.size() == 1) {
                String elementType = impl.substring(0, impl.length() - 2);
                return "new " + formatGenericCommas(elementType) + "[" + renderedArgs.getFirst() + "]";
            }
            return "new " + formatGenericCommas(impl) + "(" + String.join(", ", renderedArgs) + ")";
        }
        if (ast instanceof PropertyAccessExpression property) {
            String receiverText = render(property.receiver(), context);
            TypeGuess receiverType = property.receiver().resolvedType().isKnown() ? property.receiver().resolvedType() : services.inferExpressionType(receiverText, context);
            if (receiverType.isKnown()) {
                String ownerType = receiverType.javaType();
                String resolvedOwner = context.activeTypeParams.contains(ownerType) ? "java.lang.Object" : ownerType;
                AffogatoSymbolResolver.PropertyHop hop = services.resolvePropertyHopOnType(resolvedOwner, property.property(), context);
                if (hop != null) {
                    FieldSymbol field = services.fieldForOwnerType(resolvedOwner, property.property(), context);
                    if ("this".equals(receiverText) && context.receiverType == null && field != null && !field.isStatic()) {
                        return receiverText + "." + property.property();
                    }
                    if (hop.call()) {
                        return receiverText + "." + hop.accessor() + "()";
                    }
                    return receiverText + "." + hop.accessor();
                }
            }
            return receiverText + "." + property.property();
        }
        if (ast instanceof AssignmentExpression assignment) {
            // Check if setter-backed property assignment
            if (assignment.target() instanceof PropertyAccessExpression prop) {
                String receiverText = render(prop.receiver(), context);
                TypeGuess receiverType = prop.receiver().resolvedType().isKnown() ? prop.receiver().resolvedType() : services.inferExpressionType(receiverText, context);
                if (receiverType.isKnown()) {
                    String ownerType = receiverType.javaType();
                    String resolvedOwner = context.activeTypeParams.contains(ownerType) ? "java.lang.Object" : ownerType;
                    FieldSymbol field = services.fieldForOwnerType(resolvedOwner, prop.property(), context);
                    String valueText = render(assignment.value(), context);
                    
                    if (services.isGetterSetterBackedPropertyAccess(prop, context)) {
                        if ("this".equals(receiverText) && context.receiverType == null && field != null && !field.isStatic()) {
                            return receiverText + "." + prop.property() + " " + assignment.operator() + " " + valueText;
                        }
                        if (field != null && field.isStatic()) {
                            return receiverText + "." + prop.property() + " " + assignment.operator() + " " + valueText;
                        }
                        String accessor = field != null ? services.getterName(prop.property(), field.type()) : services.getterName(prop.property(), TypeRef.unspecified("Object"));
                        if (accessor == null || accessor.isEmpty()) {
                            accessor = context.javaResolver.getterInvocationName(resolvedOwner, prop.property(), context.unit)
                                    .orElse(services.getterName(prop.property(), TypeRef.unspecified("Object")));
                        }
                        String setter = services.setterName(prop.property());
                        String type = "Object";
                        if (field != null) {
                            type = mapPrimitive(field.type().javaType());
                        }

                        if (assignment.operator().equals("=")) {
                            return receiverText + "." + setter + "(" + valueText + ")";
                        } else {
                            String op = assignment.operator().substring(0, assignment.operator().length() - 1);
                            if (!receiverText.contains("(")) {
                                // Simple receiver: safe to reference twice.
                                return receiverText + "." + setter + "(" + receiverText + "." + accessor + "() " + op + " (" + valueText + "))";
                            } else {
                                // Complex receiver (method call): hoist to temp var to avoid double evaluation.
                                String recv = context.nextRecvTempName();
                                return "var " + recv + " = " + receiverText + "; " + recv + "." + setter
                                        + "(" + recv + "." + accessor + "() " + op + " (" + valueText + "))";
                            }
                        }
                    }
                }
            }
            return render(assignment.target(), context) + " " + assignment.operator() + " " + render(assignment.value(), context);
        }
        if (ast instanceof TernaryExpression ternary) {
            return render(ternary.condition(), context) + " ? " + render(ternary.thenExpression(), context) + " : " + render(ternary.elseExpression(), context);
        }
        if (ast instanceof InstanceOfExpression instanceOf) {
            return render(instanceOf.expression(), context) + " instanceof " + erasedInstanceOfType(instanceOf.targetType());
        }
        if (ast instanceof BinaryExpression binary) {
            return renderBinary(binary, context);
        }
        if (ast instanceof UnaryExpression unary) {
            if (unary.operator().equals("++") || unary.operator().equals("--")) {
                if (unary.expression() instanceof PropertyAccessExpression prop && services.isGetterSetterBackedPropertyAccess(prop, context)) {
                    String receiverText = render(prop.receiver(), context);
                    TypeGuess receiverType = prop.receiver().resolvedType().isKnown() ? prop.receiver().resolvedType() : services.inferExpressionType(receiverText, context);
                    String ownerType = receiverType.javaType();
                    String resolvedOwner = context.activeTypeParams.contains(ownerType) ? "java.lang.Object" : ownerType;
                    FieldSymbol field = services.fieldForOwnerType(resolvedOwner, prop.property(), context);
                    if ("this".equals(receiverText) && context.receiverType == null && field != null && !field.isStatic()) {
                        if (ast.source().trim().endsWith("++") || ast.source().trim().endsWith("--")) {
                            return receiverText + "." + prop.property() + unary.operator();
                        }
                        return unary.operator() + receiverText + "." + prop.property();
                    }
                    String accessor = field != null ? services.getterName(prop.property(), field.type()) : services.getterName(prop.property(), TypeRef.unspecified("Object"));
                    String setter = services.setterName(prop.property());
                    String op = unary.operator().substring(0, 1);
                    
                    boolean isPostfix = ast.source().trim().endsWith("++") || ast.source().trim().endsWith("--");
                    String type = "Object";
                    if (field != null) {
                        type = mapPrimitive(field.type().javaType());
                    }
                    
                    if (isPostfix) {
                        return "((java.util.function.Supplier<" + type + ">) () -> { " +
                                type + " _v = " + receiverText + "." + accessor + "(); " +
                                receiverText + "." + setter + "(_v " + op + " 1); " +
                                "return _v; }).get()";
                    } else {
                        return "((java.util.function.Supplier<" + type + ">) () -> { " +
                                type + " _v = " + receiverText + "." + accessor + "() " + op + " 1; " +
                                receiverText + "." + setter + "(_v); " +
                                "return _v; }).get()";
                    }
                }
                // Check if postfix
                if (ast.source().trim().endsWith("++") || ast.source().trim().endsWith("--")) {
                    return render(unary.expression(), context) + unary.operator();
                } else {
                    return unary.operator() + render(unary.expression(), context);
                }
            }
            if (unary.operator().equals("!")) {
                AstExpression operand = unary.expression();
                if (operand instanceof IdentifierExpression || operand instanceof PropertyAccessExpression
                        || operand instanceof LiteralExpression) {
                    return "!" + render(operand, context);
                }
                return "!(" + render(operand, context) + ")";
            }
            return unary.operator() + render(unary.expression(), context);
        }
        if (ast instanceof CastExpression cast) {
            TypeGuess sourceType = cast.expression().resolvedType().isKnown()
                    ? cast.expression().resolvedType()
                    : services.inferExpressionType(cast.expression().source(), context);
            String rendered = render(cast.expression(), context);
            String primitiveTarget = primitiveCastTarget(cast.targetType());
            if (primitiveTarget != null
                    && sourceType.isKnown()
                    && !sourceType.isNullLiteral()
                    && primitiveCastTarget(sourceType.javaType()) == null) {
                return renderPrimitiveUnboxingCast(rendered, primitiveTarget);
            }
            return "((" + formatGenericCommas(cast.targetType()) + ") " + render(cast.expression(), context) + ")";
        }
        if (ast instanceof LambdaExpression lambda) {
            if (!lambda.parametersSource().isBlank() && lambda.expressionBody() != null) {
                return renderLambdaParameters(lambda.parametersSource()) + " -> " + render(lambda.expressionBody(), context);
            }
            AffogatoLexer lexer = new AffogatoLexer(CharStreams.fromString(lambda.source()));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            AffogatoParser parser = new AffogatoParser(tokens);
            AffogatoParser.LambdaExpressionContext lambdaCtx = parser.lambdaExpression();
            if (lambdaCtx != null && lambdaCtx.ARROW() != null) {
                String params = renderLambdaParameters(lambdaCtx.lambdaParameters());
                AffogatoParser.LambdaBodyContext bodyCtx = lambdaCtx.lambdaBody();
                if (bodyCtx.expression() != null) {
                    AstExpression bodyExpr = services.expressionAst(bodyCtx.expression().getText(), context);
                    return params + " -> " + render(bodyExpr, context);
                } else if (bodyCtx.block() != null) {
                    StringBuilder blockSb = new StringBuilder();
                    blockSb.append("{\n");
                    CompilationUnit lambdaUnit = AffogatoTypeChecker.withSource(context.unit, lambda.source());
                    services.writeBlockStatements(blockSb, lambdaUnit, bodyCtx.block(), context, 1);
                    blockSb.append("}");
                    String blockStr = blockSb.toString();
                    // Collapse empty blocks to `{}`
                    if (blockStr.equals("{\n}")) {
                        blockStr = "{}";
                    }
                    return params + " -> " + blockStr;
                }
            }
            return lambda.source();
        }
        if (ast instanceof MethodReferenceExpression methodRef) {
            return methodRef.source();
        }
        if (ast instanceof SwitchExpressionNode switchNode) {
            return services.buildSwitchExpressionNode(switchNode.source(), context).javaSource();
        }
        if (ast instanceof SafeCallExpression) {
            // SafeCall (?.) is rejected by scanUnsupportedSourceEdges before codegen runs.
            // If we somehow reach this branch, return the raw source so javac surfaces the issue
            // rather than generating silently invalid Java (the previous hash-named temp variable
            // was never declared, making the generated code syntactically broken).
            return ast.source();
        }
        if (ast instanceof ElvisExpression) {
            // Elvis (?:) is rejected by scanUnsupportedSourceEdges before codegen runs.
            // Same defensive fallback as SafeCallExpression above.
            return ast.source();
        }
        if (ast instanceof UnknownExpression unknown) {
            return unknown.source();
        }
        if (ast instanceof UnsupportedExpression unsupported) {
            return unsupported.source();
        }
        return ast.source();
    }

    private String renderLambdaParameters(AffogatoParser.LambdaParametersContext paramsCtx) {
        if (paramsCtx.Identifier() != null) {
            return paramsCtx.Identifier().getText();
        }
        if (paramsCtx.lambdaParameterList() == null) {
            return "()";
        }
        List<String> params = new ArrayList<>();
        for (AffogatoParser.LambdaParameterContext param : paramsCtx.lambdaParameterList().lambdaParameter()) {
            if (param.typeRef() != null) {
                String typeName = services.stripNullableSuffix(param.typeRef().getText());
                typeName = mapPrimitive(typeName);
                params.add(typeName + " " + param.Identifier().getText());
            } else {
                params.add(param.Identifier().getText());
            }
        }
        return "(" + String.join(", ", params) + ")";
    }

    private String renderLambdaParameters(String paramsSource) {
        String params = paramsSource.trim();
        if (!params.startsWith("(")) {
            return params;
        }
        if (params.equals("()")) {
            return params;
        }
        String inner = params.substring(1, params.length() - 1).trim();
        if (inner.isBlank()) {
            return "()";
        }
        List<String> rendered = new ArrayList<>();
        for (String param : splitTopLevel(inner, ',')) {
            String trimmed = param.trim();
            int colon = topLevelColon(trimmed);
            if (colon < 0) {
                rendered.add(trimmed);
                continue;
            }
            String name = trimmed.substring(0, colon).trim();
            String typeName = services.stripNullableSuffix(trimmed.substring(colon + 1).trim());
            rendered.add(mapPrimitive(typeName) + " " + name);
        }
        return "(" + String.join(", ", rendered) + ")";
    }

    private List<String> splitTopLevel(String text, char delimiter) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (ch == '<' || ch == '(' || ch == '[') {
                depth++;
            } else if (ch == '>' || ch == ')' || ch == ']') {
                depth = Math.max(0, depth - 1);
            } else if (ch == delimiter && depth == 0) {
                parts.add(text.substring(start, index));
                start = index + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    private int topLevelColon(String text) {
        int depth = 0;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (ch == '<' || ch == '(' || ch == '[') {
                depth++;
            } else if (ch == '>' || ch == ')' || ch == ']') {
                depth = Math.max(0, depth - 1);
            } else if (ch == ':' && depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private String mapPrimitive(String type) {
        return switch (type) {
            case "Int" -> "int";
            case "Long" -> "long";
            case "Double" -> "double";
            case "Float" -> "float";
            case "Boolean" -> "boolean";
            case "Char" -> "char";
            case "Byte" -> "byte";
            case "Short" -> "short";
            default -> type;
        };
    }

    private String primitiveCastTarget(String type) {
        return switch (type) {
            case "byte", "short", "int", "long", "float", "double", "boolean", "char" -> type;
            default -> null;
        };
    }

    private String renderPrimitiveUnboxingCast(String expression, String target) {
        return switch (target) {
            case "byte" -> "((Number) " + expression + ").byteValue()";
            case "short" -> "((Number) " + expression + ").shortValue()";
            case "int" -> "((Number) " + expression + ").intValue()";
            case "long" -> "((Number) " + expression + ").longValue()";
            case "float" -> "((Number) " + expression + ").floatValue()";
            case "double" -> "((Number) " + expression + ").doubleValue()";
            case "boolean" -> "((Boolean) " + expression + ").booleanValue()";
            case "char" -> "((Character) " + expression + ").charValue()";
            default -> "((" + target + ") " + expression + ")";
        };
    }

    private String renderNestedArrayInitializer(ArrayLiteralExpression array, MethodContext context) {
        List<String> elements = new ArrayList<>();
        for (AstExpression element : array.elements()) {
            elements.add(render(element, context));
        }
        return "{" + String.join(", ", elements) + "}";
    }

    private String renderListBuilder(LambdaExpression lambda, String elementType, MethodContext context) {
        String lambdaSource = lambda.source();
        AffogatoLexer lexer = new AffogatoLexer(CharStreams.fromString(lambdaSource));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        AffogatoParser parser = new AffogatoParser(tokens);
        AffogatoParser.LambdaExpressionContext lambdaCtx = parser.lambdaExpression();
        if (lambdaCtx != null && lambdaCtx.ARROW() != null) {
            AffogatoParser.LambdaBodyContext bodyCtx = lambdaCtx.lambdaBody();
            if (bodyCtx.block() != null) {
                StringBuilder blockSb = new StringBuilder();
                blockSb.append("{\n");
                blockSb.append("java.util.List<").append(elementType).append("> $children = new java.util.ArrayList<>();\n");
                for (AffogatoParser.StatementContext statement : bodyCtx.block().statement()) {
                    if (statement.separators() != null) {
                        continue;
                    }
                    if (statement.expressionStatement() != null) {
                        String rawExpr = services.mergeTrailingClosure(
                                services.sourceText(lambdaSource, statement.expressionStatement().expression()),
                                lambdaSource, statement.expressionStatement().trailingClosure(), context);
                        AstExpression childExpr = services.expressionAst(rawExpr, context);
                        String renderedChild = render(childExpr, context);
                        blockSb.append("$children.add(").append(renderedChild).append(");\n");
                    } else {
                        CompilationUnit lambdaUnit = AffogatoTypeChecker.withSource(context.unit, lambdaSource);
                        services.writeStatement(blockSb, lambdaUnit, statement, context, 0);
                    }
                }
                blockSb.append("return $children;\n");
                blockSb.append("}");
                return "() -> " + blockSb.toString();
            }
        }
        return render(lambda, context);
    }

    private String renderBinary(BinaryExpression binary, MethodContext context) {
        String operator = binary.operator();
        int parentPrec = operatorPrecedence(operator);
        String left = renderBinaryOperand(binary.left(), parentPrec, true, operator, context);
        String right = renderBinaryOperand(binary.right(), parentPrec, false, operator, context);
        return left + " " + operator + " " + right;
    }

    private String renderBinaryOperand(AstExpression operand, int parentPrec, boolean leftSide, String parentOp, MethodContext context) {
        if (operand instanceof BinaryExpression child) {
            int childPrec = operatorPrecedence(child.operator());
            boolean needsParen = childPrec < parentPrec
                    || (childPrec == parentPrec && !leftSide && !isRightAssociative(parentOp));
            if (needsParen) {
                return "(" + render(child, context) + ")";
            }
        }
        return render(operand, context);
    }

    private static int operatorPrecedence(String operator) {
        return switch (operator) {
            case "*", "/", "%" -> 60;
            case "+", "-" -> 50;
            case "<<", ">>", ">>>" -> 45;
            case "<", "<=", ">", ">=" -> 40;
            case "==", "!=" -> 35;
            case "&" -> 30;
            case "^" -> 25;
            case "|" -> 20;
            case "&&" -> 15;
            case "||" -> 10;
            default -> 0;
        };
    }

    private static boolean isRightAssociative(String operator) {
        return "=".equals(operator) || operator.endsWith("=");
    }

    static String formatGenericCommas(String type) {
        if (type == null || type.indexOf('<') < 0) {
            return type;
        }
        StringBuilder out = new StringBuilder();
        int depth = 0;
        for (int index = 0; index < type.length(); index++) {
            char current = type.charAt(index);
            if (current == '<') {
                depth++;
            } else if (current == '>') {
                depth--;
            } else if (current == ',' && depth > 0) {
                out.append(", ");
                if (index + 1 < type.length() && type.charAt(index + 1) == ' ') {
                    index++;
                }
                continue;
            }
            out.append(current);
        }
        return out.toString();
    }

    /** Java rejects parameterized types in {@code instanceof}; emit the erasure instead. */
    private static String erasedInstanceOfType(String targetType) {
        String trimmed = targetType.trim();
        int generic = trimmed.indexOf('<');
        return generic > 0 ? trimmed.substring(0, generic) : trimmed;
    }
}
