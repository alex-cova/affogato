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
    private final AffogatoTranspiler transpiler;

    ExpressionRenderer(AffogatoTranspiler transpiler) {
        this.transpiler = transpiler;
    }

    String render(AstExpression ast, MethodContext context) {
        if (ast instanceof LiteralExpression literal) {
            String source = literal.source();
            if (source.startsWith("\"")) {
                return transpiler.transformStringInterpolation(source, context);
            }
            return source;
        }
        if (ast instanceof ArrayLiteralExpression arrayLiteral) {
            List<String> renderedElements = new ArrayList<>();
            for (AstExpression el : arrayLiteral.elements()) {
                renderedElements.add(render(el, context));
            }
            String expected = transpiler.getExpectedArrayElementType();
            String elementType = expected != null ? expected : transpiler.inferArrayElementType(renderedElements, context);
            // clear expected array type during recursion to match old behavior
            String savedExpected = transpiler.getExpectedArrayElementType();
            transpiler.setExpectedArrayElementType(null);
            List<String> innerRendered = new ArrayList<>();
            for (AstExpression el : arrayLiteral.elements()) {
                innerRendered.add(render(el, context));
            }
            transpiler.setExpectedArrayElementType(savedExpected);
            return "new " + elementType + "[]{" + String.join(", ", innerRendered) + "}";
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
                AffogatoTranspiler.PropertyHop hop = transpiler.resolvePropertyHopOnType(resolvedReceiverType, name, context);
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
                    TypeGuess valType = named.expression().resolvedType().isKnown() ? named.expression().resolvedType() : transpiler.inferExpressionType(valStr, context);
                    typedArgs.add(new TypedArgument(named.name(), valStr, valType, named.expression()));
                } else {
                    String valStr = render(arg, context);
                    TypeGuess valType = arg.resolvedType().isKnown() ? arg.resolvedType() : transpiler.inferExpressionType(valStr, context);
                    typedArgs.add(new TypedArgument("", valStr, valType, arg));
                }
            }

            // Check if extension call
            if (call.receiver() != null && !(call.receiver() instanceof UnknownExpression)) {
                String receiverText = render(call.receiver(), context);
                TypeGuess receiverType = call.receiver().resolvedType().isKnown() ? call.receiver().resolvedType() : transpiler.inferExpressionType(receiverText, context);
                String rawOwner = receiverType.javaType();
                String resolvedOwner = context.activeTypeParams.contains(rawOwner) ? "java.lang.Object" : rawOwner;
                Optional<ExtensionMatch> match = context.dispatchExtension(transpiler.simpleTypeName(resolvedOwner), simpleName, typedArgs);
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
            String impl = transpiler.constructorImplementation(constructor.typeName());
            
            // Build arguments
            List<TypedArgument> typedArgs = new ArrayList<>();
            boolean hasNamed = false;
            for (AstExpression arg : constructor.arguments()) {
                if (arg instanceof NamedArgumentExpression named) {
                    hasNamed = true;
                    String valStr = render(named.expression(), context);
                    TypeGuess valType = named.expression().resolvedType().isKnown() ? named.expression().resolvedType() : transpiler.inferExpressionType(valStr, context);
                    typedArgs.add(new TypedArgument(named.name(), valStr, valType, named.expression()));
                } else {
                    String valStr = render(arg, context);
                    TypeGuess valType = arg.resolvedType().isKnown() ? arg.resolvedType() : transpiler.inferExpressionType(valStr, context);
                    typedArgs.add(new TypedArgument("", valStr, valType, arg));
                }
            }

            List<String> renderedArgs = new ArrayList<>();
            if (hasNamed) {
                ClassSymbol affogatoTarget = transpiler.classSymbol(constructor.typeName(), context.unit);
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
                    String lastParamType = transpiler.lastParameterType(constructor.typeName(), context);
                    String elementType = transpiler.supplierListElementType(lastParamType);
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

            return "new " + impl + "(" + String.join(", ", renderedArgs) + ")";
        }
        if (ast instanceof PropertyAccessExpression property) {
            String receiverText = render(property.receiver(), context);
            TypeGuess receiverType = property.receiver().resolvedType().isKnown() ? property.receiver().resolvedType() : transpiler.inferExpressionType(receiverText, context);
            if (receiverType.isKnown()) {
                String ownerType = receiverType.javaType();
                String resolvedOwner = context.activeTypeParams.contains(ownerType) ? "java.lang.Object" : ownerType;
                AffogatoTranspiler.PropertyHop hop = transpiler.resolvePropertyHopOnType(resolvedOwner, property.property(), context);
                if (hop != null) {
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
                TypeGuess receiverType = prop.receiver().resolvedType().isKnown() ? prop.receiver().resolvedType() : transpiler.inferExpressionType(receiverText, context);
                if (receiverType.isKnown()) {
                    String ownerType = receiverType.javaType();
                    String resolvedOwner = context.activeTypeParams.contains(ownerType) ? "java.lang.Object" : ownerType;
                    FieldSymbol field = transpiler.fieldForOwnerType(resolvedOwner, prop.property(), context);
                    String valueText = render(assignment.value(), context);
                    
                    if (transpiler.isGetterSetterBackedPropertyAccess(prop, context)) {
                        String accessor = field != null ? transpiler.getterName(prop.property(), field.type()) : transpiler.getterName(prop.property(), TypeRef.unspecified("Object"));
                        if (accessor == null || accessor.isEmpty()) {
                            accessor = context.javaResolver.getterInvocationName(resolvedOwner, prop.property(), context.unit)
                                    .orElse(transpiler.getterName(prop.property(), TypeRef.unspecified("Object")));
                        }
                        String setter = transpiler.setterName(prop.property());
                        String type = "Object";
                        if (field != null) {
                            type = mapPrimitive(field.type().javaType());
                        }

                        if (assignment.operator().equals("=")) {
                            return receiverText + "." + setter + "(" + valueText + ")";
                        } else {
                            String op = assignment.operator().substring(0, assignment.operator().length() - 1);
                            return receiverText + "." + setter + "(" + receiverText + "." + accessor + "() " + op + " (" + valueText + "))";
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
            return render(instanceOf.expression(), context) + " instanceof " + instanceOf.targetType();
        }
        if (ast instanceof BinaryExpression binary) {
            return render(binary.left(), context) + " " + binary.operator() + " " + render(binary.right(), context);
        }
        if (ast instanceof UnaryExpression unary) {
            if (unary.operator().equals("++") || unary.operator().equals("--")) {
                if (unary.expression() instanceof PropertyAccessExpression prop && transpiler.isGetterSetterBackedPropertyAccess(prop, context)) {
                    String receiverText = render(prop.receiver(), context);
                    TypeGuess receiverType = prop.receiver().resolvedType().isKnown() ? prop.receiver().resolvedType() : transpiler.inferExpressionType(receiverText, context);
                    String ownerType = receiverType.javaType();
                    String resolvedOwner = context.activeTypeParams.contains(ownerType) ? "java.lang.Object" : ownerType;
                    FieldSymbol field = transpiler.fieldForOwnerType(resolvedOwner, prop.property(), context);
                    String accessor = field != null ? transpiler.getterName(prop.property(), field.type()) : transpiler.getterName(prop.property(), TypeRef.unspecified("Object"));
                    String setter = transpiler.setterName(prop.property());
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
                return "!(" + render(unary.expression(), context) + ")";
            }
            return unary.operator() + render(unary.expression(), context);
        }
        if (ast instanceof CastExpression cast) {
            return "((" + cast.targetType() + ") " + render(cast.expression(), context) + ")";
        }
        if (ast instanceof LambdaExpression lambda) {
            AffogatoLexer lexer = new AffogatoLexer(CharStreams.fromString(lambda.source()));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            AffogatoParser parser = new AffogatoParser(tokens);
            AffogatoParser.LambdaExpressionContext lambdaCtx = parser.lambdaExpression();
            if (lambdaCtx != null && lambdaCtx.ARROW() != null) {
                String params = renderLambdaParameters(lambdaCtx.lambdaParameters());
                AffogatoParser.LambdaBodyContext bodyCtx = lambdaCtx.lambdaBody();
                if (bodyCtx.expression() != null) {
                    AstExpression bodyExpr = transpiler.expressionAst(bodyCtx.expression().getText(), context);
                    return params + " -> " + render(bodyExpr, context);
                } else if (bodyCtx.block() != null) {
                    StringBuilder blockSb = new StringBuilder();
                    blockSb.append("{\n");
                    transpiler.writeBlockStatements(blockSb, context.unit, bodyCtx.block(), context, 1);
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
            return transpiler.buildSwitchExpressionNode(switchNode.source(), context).javaSource();
        }
        if (ast instanceof SafeCallExpression safeCall) {
            String receiverText = render(safeCall.receiver(), context);
            TypeGuess receiverType = safeCall.receiver().resolvedType().isKnown() ? safeCall.receiver().resolvedType() : transpiler.inferExpressionType(receiverText, context);
            String property = safeCall.property();
            
            // Simple desugaring: (temp = receiver) != null ? temp.property : null
            // We need a unique temporary variable name. For now, let's use a simple scheme.
            String temp = "$safe_" + Math.abs(receiverText.hashCode() % 1000);
            
            // If it's a method call, it will be in the property string as "name(args)"
            String access = property.contains("(") ? "." + property : "." + property;
            // Actually, for records/classes it might be .property() or .property.
            // PropertyHop can help here too.
            if (!property.contains("(")) {
                 String ownerType = receiverType.javaType();
                 String resolvedOwner = context.activeTypeParams.contains(ownerType) ? "java.lang.Object" : ownerType;
                 AffogatoTranspiler.PropertyHop hop = transpiler.resolvePropertyHopOnType(resolvedOwner, property, context);
                 if (hop != null) {
                     access = "." + hop.accessor() + (hop.call() ? "()" : "");
                 } else {
                     access = "." + property;
                 }
            } else {
                access = "." + property;
            }

            return "((" + temp + " = " + receiverText + ") != null ? " + temp + access + " : null)";
        }
        if (ast instanceof ElvisExpression elvis) {
            String leftText = render(elvis.left(), context);
            String rightText = render(elvis.right(), context);
            String temp = "$elvis_" + Math.abs(leftText.hashCode() % 1000);
            return "((" + temp + " = " + leftText + ") != null ? " + temp + " : " + rightText + ")";
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
                String typeName = transpiler.stripNullableSuffix(param.typeRef().getText());
                typeName = mapPrimitive(typeName);
                params.add(typeName + " " + param.Identifier().getText());
            } else {
                params.add(param.Identifier().getText());
            }
        }
        return "(" + String.join(", ", params) + ")";
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
                        String rawExpr = transpiler.mergeTrailingClosure(
                                transpiler.sourceText(lambdaSource, statement.expressionStatement().expression()),
                                lambdaSource, statement.expressionStatement().trailingClosure(), context);
                        AstExpression childExpr = transpiler.expressionAst(rawExpr, context);
                        String renderedChild = render(childExpr, context);
                        blockSb.append("$children.add(").append(renderedChild).append(");\n");
                    } else {
                        transpiler.writeStatement(blockSb, context.unit, statement, context, 0);
                    }
                }
                blockSb.append("return $children;\n");
                blockSb.append("}");
                return "() -> " + blockSb.toString();
            }
        }
        return render(lambda, context);
    }
}
