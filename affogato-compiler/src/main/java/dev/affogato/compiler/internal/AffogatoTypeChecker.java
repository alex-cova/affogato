package dev.affogato.compiler.internal;

import dev.affogato.compiler.AffogatoDiagnostic;
import dev.affogato.compiler.SourceLocations;
import dev.affogato.compiler.parser.AffogatoParser;
import org.antlr.v4.runtime.ParserRuleContext;
import static dev.affogato.compiler.internal.TranspilerTypes.*;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AffogatoTypeChecker {
    private static final Pattern INSTANCEOF_ALIAS = Pattern.compile(
            "\\b([A-Za-z_][A-Za-z0-9_.$]*)\\s+is\\s+([A-Za-z_][A-Za-z0-9_.$]*(?:<[^>]+>)?)"
    );
    private static final Pattern AS_CAST = Pattern.compile(
            "\\b([A-Za-z_][A-Za-z0-9_.$]*)\\s+as\\s+([A-Za-z_][A-Za-z0-9_.$]*(?:<[^>]+>)?\\??)"
    );
    private static final Pattern VARIABLE_ASSIGNMENT = Pattern.compile(
            "^([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.+)$"
    );

    final List<AffogatoDiagnostic> diagnostics;
    private final AffogatoSymbolResolver symbols;
    private final ClassSymbolTable classSymbols;
    private final Map<String, List<ExtensionSymbol>> extensionSymbols;
    private final JavaResolver javaResolver;
    private final FlowAnalyzer flow;
    private final ExpressionRenderServices renderServices;
    private final AffogatoParserRunner parserRunner;
    private Set<String> activeTypeParams = new HashSet<>();

    AffogatoTypeChecker(
            List<AffogatoDiagnostic> diagnostics,
            AffogatoSymbolResolver symbols,
            FlowAnalyzer flow,
            ExpressionRenderServices renderServices,
            AffogatoParserRunner parserRunner
    ) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.symbols = Objects.requireNonNull(symbols, "symbols");
        this.classSymbols = symbols.classSymbols();
        this.extensionSymbols = symbols.extensionSymbols();
        this.javaResolver = symbols.javaResolver();
        this.flow = Objects.requireNonNull(flow, "flow");
        this.renderServices = Objects.requireNonNull(renderServices, "renderServices");
        this.parserRunner = Objects.requireNonNull(parserRunner, "parserRunner");
    }

    void setActiveTypeParams(Set<String> activeTypeParams) {
        this.activeTypeParams = activeTypeParams;
    }


    public void typeCheck(AffogatoTranspiler.ParsedUnit parsedUnit) {
        CompilationUnit unit = parsedUnit.unit();
        for (ParsedClass clazz : unit.classes()) {
            typeCheckClass(unit, clazz);
        }
        for (ParsedInterface parsedInterface : unit.interfaces()) {
            typeCheckInterface(unit, parsedInterface);
        }
        for (ParsedRecord parsedRecord : unit.records()) {
            typeCheckRecord(unit, parsedRecord);
        }
        if (!unit.extensions().isEmpty()) {
            typeCheckExtensions(unit);
        }
    }

    private void typeCheckClass(CompilationUnit unit, ParsedClass clazz) {
        Set<String> prev = activeTypeParams;
        activeTypeParams = new HashSet<>(prev);
        clazz.typeParameters().forEach(tp -> activeTypeParams.add(tp.name()));
        MethodContext fieldContext = MethodContext.empty(unit, clazz, classSymbols, extensionSymbols, javaResolver, activeTypeParams);
        for (FieldDecl field : clazz.fields()) {
            if (field.initializer() != null && !field.initializer().isBlank()) {
                validateAssignment(field.type(), field.initializer(), fieldContext, field.line(), 1,
                        "AFFOGATO_FIELD_TYPE", "Field initializer type is not assignable to " + field.type().javaType() + ".");
            }
        }
        for (ConstructorDecl constructor : clazz.constructors()) {
            typeCheckExecutable(unit, clazz, "<init>", TypeRef.unspecified("void"), constructor.parameters(), constructor.body());
        }
        for (MethodDecl method : clazz.methods()) {
            Set<String> methodTypeParams = new HashSet<>(activeTypeParams);
            if (!method.typeParameters().isEmpty()) {
                method.typeParameters().forEach(tp -> methodTypeParams.add(tp.name()));
            }
            activeTypeParams = methodTypeParams;
            if (method.body() != null) {
                typeCheckExecutable(unit, clazz, method.name(), method.returnType(), method.parameters(), method.body());
            }
        }
        activeTypeParams = prev;
    }

    private void typeCheckInterface(CompilationUnit unit, ParsedInterface parsedInterface) {
        ParsedClass shape = new ParsedClass(parsedInterface.access(), parsedInterface.name(), parsedInterface.typeParameters(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), parsedInterface.declarationLine(), parsedInterface.declarationColumn());
        Set<String> prev = activeTypeParams;
        activeTypeParams = new HashSet<>(prev);
        parsedInterface.typeParameters().forEach(tp -> activeTypeParams.add(tp.name()));
        for (InterfaceMethod method : parsedInterface.methods()) {
            if (method.body() != null) {
                typeCheckExecutable(unit, shape, method.name(), method.returnType(), method.parameters(), method.body());
            }
        }
        activeTypeParams = prev;
    }

    private void typeCheckRecord(CompilationUnit unit, ParsedRecord parsedRecord) {
        List<FieldDecl> componentFields = parsedRecord.components().stream()
                .map(component -> new FieldDecl("public", false, false, component.name(), component.type(), "",
                        parsedRecord.declarationLine(), component.annotations()))
                .toList();
        ParsedClass shape = new ParsedClass(parsedRecord.access(), parsedRecord.name(), parsedRecord.typeParameters(),
                List.of(), List.of(), componentFields, List.of(), List.of(), List.of(), parsedRecord.declarationLine(), parsedRecord.declarationColumn());
        Set<String> prev = activeTypeParams;
        activeTypeParams = new HashSet<>(prev);
        parsedRecord.typeParameters().forEach(tp -> activeTypeParams.add(tp.name()));
        for (MethodDecl method : parsedRecord.methods()) {
            if (method.body() != null) {
                typeCheckExecutable(unit, shape, method.name(), method.returnType(), method.parameters(), method.body());
            }
        }
        activeTypeParams = prev;
    }

    private void typeCheckExtensions(CompilationUnit unit) {
        String holderName = extensionsHolderName(unit);
        ParsedClass shape = new ParsedClass("public", holderName, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 1, 1);
        for (ExtensionFuncDecl extension : unit.extensions()) {
            List<ParamDecl> parameters = new ArrayList<>();
            parameters.add(new ParamDecl("$this", extension.receiverType(), PropertyKind.NONE, List.of()));
            parameters.addAll(extension.parameters());
            typeCheckExecutable(unit, shape, extension.name(), extension.returnType(), parameters, extension.body(), extension.receiverType().javaType());
        }
    }

    private static String extensionsHolderName(CompilationUnit unit) {
        String fileName = unit.sourceFile().getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot >= 0 ? fileName.substring(0, dot) : fileName;
        return base + "Extensions";
    }

    private void typeCheckExecutable(CompilationUnit unit, ParsedClass clazz, String name, TypeRef returnType,
                                     List<ParamDecl> parameters, AffogatoParser.BlockContext body) {
        typeCheckExecutable(unit, clazz, name, returnType, parameters, body, null);
    }

    private void typeCheckExecutable(CompilationUnit unit, ParsedClass clazz, String name, TypeRef returnType,
                                     List<ParamDecl> parameters, AffogatoParser.BlockContext body, String receiverType) {
        MethodContext context = MethodContext.forExecutable(unit, clazz, name, returnType, classSymbols, extensionSymbols, javaResolver, activeTypeParams);
        if (receiverType != null) {
            context.receiverType = receiverType;
        }
        for (ParamDecl parameter : parameters) {
            validateTypeRef(parameter.type(), unit, 1, 1);
            if (context.variableTypes.containsKey(parameter.name())) {
                diagnostics.add(error(unit.sourceFile(), 1, 1, parameter.name().length(),
                        "AFFOGATO_DUPLICATE_LOCAL",
                        "Duplicate local variable '" + parameter.name() + "' shadows another parameter or local in the generated Java scope."));
            }
            context.declareVariable(parameter.name(), parameter.type(), true);
        }
        validateTypeRef(returnType, unit, 1, 1);
        if (!returnType.javaType().equals("void") && !flow.blockExits(body)) {
            diagnostics.add(error(unit.sourceFile(), 1, 1, "AFFOGATO_RETURN_FLOW", "Method " + name + " must exit with a value on all paths."));
        }
        checkBlock(unit, body, context);
    }

    private void checkBlock(CompilationUnit unit, AffogatoParser.BlockContext block, MethodContext context) {
        MethodContext.ScopeSnapshot scope = context.snapshotScope();
        checkBlockStatements(unit, block, context);
        context.restoreScope(scope);
    }

    private void checkBlockStatements(CompilationUnit unit, AffogatoParser.BlockContext block, MethodContext context) {
        flow.checkUnreachable(unit.sourceFile(), block);
        context.pushBlockScope();
        try {
            List<AffogatoParser.StatementContext> statements = block.statement();
            for (int index = 0; index < statements.size(); index++) {
                AffogatoParser.StatementContext statement = statements.get(index);
                Set<String> declaredLater = new LinkedHashSet<>();
                for (int later = index + 1; later < statements.size(); later++) {
                    declaredLater.addAll(localNamesDeclaredInStatement(statements.get(later)));
                }
                context.setLocalsDeclaredLaterInBlock(declaredLater);
                checkStatement(unit, statement, context);
            }
            context.setLocalsDeclaredLaterInBlock(Set.of());
        } finally {
            context.popBlockScope();
        }
    }

    private Set<String> localNamesDeclaredInStatement(AffogatoParser.StatementContext statement) {
        if (statement.localVarDecl() != null) {
            return Set.of(statement.localVarDecl().Identifier().getText());
        }
        return Set.of();
    }

    private void checkStatement(CompilationUnit unit, AffogatoParser.StatementContext statement, MethodContext context) {
        context.currentLine = statement.getStart().getLine();
        context.currentColumn = statement.getStart().getCharPositionInLine() + 1;
        if (statement.block() != null) { checkBlock(unit, statement.block(), context); return; }
        if (statement.guardStatement() != null) { checkGuard(unit, statement.guardStatement(), context); return; }
        if (statement.ifStatement() != null) { checkIf(unit, statement.ifStatement(), context); return; }
        if (statement.forStatement() != null) { checkFor(unit, statement.forStatement(), context); return; }
        if (statement.whileStatement() != null) { checkWhile(unit, statement.whileStatement(), context); return; }
        if (statement.tryStatement() != null) { checkTry(unit, statement.tryStatement(), context); return; }
        if (statement.switchStatement() != null) { checkSwitch(unit, statement.switchStatement(), context); return; }
        if (statement.returnStatement() != null) { checkReturn(unit, statement.returnStatement(), context); return; }
        if (statement.throwStatement() != null) { checkThrow(unit, statement.throwStatement(), context); return; }
        if (statement.localVarDecl() != null) { checkLocalVarDecl(unit, statement.localVarDecl(), context); return; }
        if (statement.expressionStatement() != null) { checkExpressionStatement(unit, statement, context); }
    }

    private void checkGuard(CompilationUnit unit, AffogatoParser.GuardStatementContext guard, MethodContext context) {
        if (!flow.blockStopsControl(guard.block())) {
            diagnostics.add(error(unit.sourceFile(), guard.getStart().getLine(), guard.getStart().getCharPositionInLine() + 1,
                    "AFFOGATO_GUARD_FLOW", "guard else blocks must exit with return, throw, break, or continue."));
        }
        validateCondition(renderServices.sourceText(unit.source(), guard.condition()), context, guard.getStart().getLine(), guard.getStart().getCharPositionInLine() + 1);
        MethodContext.ScopeSnapshot guardScope = context.snapshotScope();
        checkBlockStatements(unit, guard.block(), context);
        context.restoreScope(guardScope);
    }

    private void checkIf(CompilationUnit unit, AffogatoParser.IfStatementContext ifStatement, MethodContext context) {
        validateCondition(renderServices.sourceText(unit.source(), ifStatement.condition()), context, ifStatement.getStart().getLine(), ifStatement.getStart().getCharPositionInLine() + 1);
        MethodContext.ScopeSnapshot thenScope = context.snapshotScope();
        checkBlockStatements(unit, ifStatement.block(0), context);
        context.restoreScope(thenScope);
        if (ifStatement.ELSE() != null) {
            if (ifStatement.ifStatement() != null) checkIf(unit, ifStatement.ifStatement(), context);
            else if (ifStatement.block().size() > 1) {
                MethodContext.ScopeSnapshot elseScope = context.snapshotScope();
                checkBlockStatements(unit, ifStatement.block(1), context);
                context.restoreScope(elseScope);
            }
        }
    }

    private void checkFor(CompilationUnit unit, AffogatoParser.ForStatementContext forStatement, MethodContext context) {
        AffogatoParser.ForContentContext content = forStatement.forCondition().forContent();
        MethodContext.ScopeSnapshot loopScope = context.snapshotScope();
        if (content.IN() != null) {
            String variable = content.Identifier().getText();
            if (!canDeclareJavaLocal(context, variable)) {
                reportJavaLocalShadow(unit, content.Identifier().getSymbol(), variable);
            }
            TypedExpression typedIterable = validateExpressionTyped(renderServices.sourceText(unit.source(), content.expression()), context, content.expression());
            Optional<TypeGuess> elementType = elementType(typedIterable.resolvedType());
            if (elementType.isPresent()) context.declareVariable(variable, TypeRef.unspecified(elementType.get().javaType()), true);
            else if (typedIterable.resolvedType().isKnown() && !typedIterable.resolvedType().isNullLiteral()) {
                diagnostics.add(error(unit.sourceFile(), forStatement.getStart().getLine(), forStatement.getStart().getCharPositionInLine() + 1,
                        "AFFOGATO_FOR_ITERABLE_TYPE", "For-in loops require an array or Iterable expression."));
            }
            context.mutableVariables.put(variable, true);
        } else {
            validateExpressionTyped(renderServices.sourceText(unit.source(), content.expression()), context, content.expression());
        }
        checkBlockStatements(unit, forStatement.block(), context);
        context.restoreScope(loopScope);
    }

    private void checkWhile(CompilationUnit unit, AffogatoParser.WhileStatementContext whileStatement, MethodContext context) {
        validateCondition(renderServices.sourceText(unit.source(), whileStatement.condition()), context, whileStatement.getStart().getLine(), whileStatement.getStart().getCharPositionInLine() + 1);
        MethodContext.ScopeSnapshot whileScope = context.snapshotScope();
        checkBlockStatements(unit, whileStatement.block(), context);
        context.restoreScope(whileScope);
    }

    private void checkTry(CompilationUnit unit, AffogatoParser.TryStatementContext tryStatement, MethodContext context) {
        MethodContext.ScopeSnapshot tryScope = context.snapshotScope();
        checkBlockStatements(unit, tryStatement.block(), context);
        context.restoreScope(tryScope);
        for (AffogatoParser.CatchClauseContext catchClause : tryStatement.catchClause()) {
            for (AffogatoParser.TypeRefContext catchTypeContext : catchClause.catchType().typeRef()) {
                TypeRef catchType = typeRef(catchTypeContext);
                validateTypeRef(catchType, unit, catchClause.getStart().getLine(), catchClause.getStart().getCharPositionInLine() + 1);
                if (!context.javaResolver.throwableCompatible(TypeGuess.of(catchType.javaType()), unit)) {
                    diagnostics.add(error(unit.sourceFile(), catchClause.getStart().getLine(), catchClause.getStart().getCharPositionInLine() + 1,
                            "AFFOGATO_CATCH_TYPE", "Catch types must be Throwable."));
                }
            }
            MethodContext catchContext = MethodContext.forExecutable(unit, context.currentClass, context.executableName, context.returnType,
                    classSymbols, extensionSymbols, javaResolver, context.activeTypeParams);
            catchContext.variableTypes.putAll(context.variableTypes);
            catchContext.mutableVariables.putAll(context.mutableVariables);
            catchContext.variableNullabilities.putAll(context.variableNullabilities);
            String catchName = catchClause.Identifier().getText();
            if (!canDeclareJavaLocal(catchContext, catchName)) {
                reportJavaLocalShadow(unit, catchClause.Identifier().getSymbol(), catchName);
            }
            catchContext.declareVariable(catchName, TypeRef.unspecified("Exception"), false);
            checkBlockStatements(unit, catchClause.block(), catchContext);
        }
        if (tryStatement.finallyClause() != null) {
            MethodContext.ScopeSnapshot finallyScope = context.snapshotScope();
            checkBlockStatements(unit, tryStatement.finallyClause().block(), context);
            context.restoreScope(finallyScope);
        }
    }

    private void checkSwitch(CompilationUnit unit, AffogatoParser.SwitchStatementContext switchStatement, MethodContext context) {
        TypedExpression typedCondition = validateExpressionTyped(renderServices.sourceText(unit.source(), switchStatement.condition()), context, switchStatement.condition());
        validateSwitchSelector(typedCondition.resolvedType(), unit, context);
        for (AffogatoParser.SwitchArmContext arm : switchStatement.switchBody().switchArm()) {
            context.currentLine = arm.getStart().getLine();
            context.currentColumn = arm.getStart().getCharPositionInLine() + 1;
            if (arm.DEFAULT() == null) {
                for (AffogatoParser.SwitchLabelContext label : arm.switchLabel()) {
                    checkSwitchLabel(typedCondition.resolvedType(), label, unit, context);
                }
            }
            AffogatoParser.SwitchArmBodyContext body = arm.switchArmBody();
            if (body.block() != null) {
                MethodContext.ScopeSnapshot armScope = context.snapshotScope();
                checkBlockStatements(unit, body.block(), context);
                context.restoreScope(armScope);
            } else {
                validateExpressionTyped(renderServices.sourceText(unit.source(), body.expression()), context, body.expression());
            }
        }
    }

    private void checkSwitchLabel(TypeGuess selectorType, AffogatoParser.SwitchLabelContext label, CompilationUnit unit, MethodContext context) {
        String rawLabel = renderServices.sourceText(unit.source(), label.expression()).trim();
        ClassSymbol enumSymbol = selectorType.isKnown() ? symbols.lookupClass(selectorType.javaType(), context.unit) : null;
        if (enumSymbol != null && enumSymbol.isEnum) {
            int dot = rawLabel.lastIndexOf('.');
            String constant = dot >= 0 ? rawLabel.substring(dot + 1).trim() : rawLabel;
            if (!enumSymbol.enumConstants.contains(constant)) {
                diagnostics.add(error(unit.sourceFile(), context.currentLine, context.currentColumn,
                        "AFFOGATO_SWITCH_LABEL_TYPE", "'" + rawLabel + "' is not a constant of enum " + enumSymbol.name() + "."));
            }
            return;
        }
        TypedExpression typedLabel = validateExpressionTyped(rawLabel, context, label.expression());
        validateSwitchLabel(selectorType, typedLabel.resolvedType(), unit, context);
    }

    private void checkReturn(CompilationUnit unit, AffogatoParser.ReturnStatementContext returnStatement, MethodContext context) {
        if (returnStatement.switchExpression() != null) {
            validateSwitchExpression(unit, returnStatement.switchExpression(), context, context.returnType,
                    "AFFOGATO_RETURN_TYPE", "Returned value is not assignable to " + context.returnType.javaType() + ".");
            return;
        }
        String rawExpression = returnStatement.expression() == null ? "" :
                renderServices.mergeTrailingClosure(renderServices.sourceText(unit.source(), returnStatement.expression()),
                        unit.source(), returnStatement.trailingClosure(), context);
        int expressionLine = returnStatement.expression() == null
                ? returnStatement.getStart().getLine()
                : returnStatement.expression().getStart().getLine();
        int expressionColumn = returnStatement.expression() == null
                ? returnStatement.getStart().getCharPositionInLine() + 1
                : returnStatement.expression().getStart().getCharPositionInLine() + 1;
        validateReturn(rawExpression, context, returnStatement.getStart().getLine(),
                returnStatement.getStart().getCharPositionInLine() + 1, expressionLine, expressionColumn);
    }

    private void checkThrow(CompilationUnit unit, AffogatoParser.ThrowStatementContext throwStatement, MethodContext context) {
        TypedExpression expression = validateExpressionTyped(renderServices.sourceText(unit.source(), throwStatement.expression()), context, throwStatement.expression());
        validateThrowExpression(expression, context, throwStatement.getStart().getLine(), throwStatement.getStart().getCharPositionInLine() + 1);
    }

    private void checkLocalVarDecl(CompilationUnit unit, AffogatoParser.LocalVarDeclContext declaration, MethodContext context) {
        boolean immutable = declaration.variableKind().LET() != null;
        String name = declaration.Identifier().getText();
        TypeRef type = declaration.typeRef() == null ? null : typeRef(declaration.typeRef());
        int declLine = declaration.getStart().getLine();
        int declCol = declaration.getStart().getCharPositionInLine() + 1;
        if (!canDeclareJavaLocal(context, name) || !context.declareBlockLocal(name)) {
            diagnostics.add(error(unit.sourceFile(), declLine, declCol, name.length(),
                    "AFFOGATO_DUPLICATE_LOCAL",
                    "Duplicate local variable '" + name + "' shadows another local or parameter in the generated Java scope."));
        }
        if (type != null) {
            validateTypeRef(type, unit, declLine, declCol);
        }
        if (declaration.switchExpression() != null) {
            TypeGuess switchType = validateSwitchExpression(unit, declaration.switchExpression(), context, type,
                    "AFFOGATO_ASSIGNMENT_TYPE", "Switch arm value is not assignable to " + (type == null ? "the inferred local type" : type.javaType()) + ".");
            if (type != null) {
                context.declareVariable(name, type, !immutable);
            } else if (switchType.isKnown() && !switchType.isNullLiteral()) {
                context.declareVariable(name, TypeRef.unspecified(switchType.javaType()), !immutable);
            }
            return;
        }
        TypedExpression typedInit = null;
        String rawInitializer = "";
        if (declaration.expression() != null) {
            rawInitializer = renderServices.mergeTrailingClosure(renderServices.sourceText(unit.source(), declaration.expression()),
                    unit.source(), declaration.trailingClosure(), context);
            if (type != null) {
                validateAssignment(type, rawInitializer, context, declLine, declCol, "AFFOGATO_ASSIGNMENT_TYPE",
                        "Assigned value is not assignable to " + type.javaType() + ".");
            } else {
                typedInit = validateExpressionTyped(rawInitializer, context, declaration.expression());
            }
        }
        if (type == null && typedInit != null) {
            TypeGuess inferred = typedInit.resolvedType();
            if (inferred.isKnown() && !inferred.isNullLiteral()) {
                type = TypeRef.unspecified(inferred.javaType());
            }
        }
        TypeRef bindingType = type;
        if (bindingType == null && typedInit != null && typedInit.resolvedType().isKnown() && !typedInit.resolvedType().isNullLiteral()) {
            bindingType = TypeRef.unspecified(typedInit.resolvedType().javaType());
        }
        if (bindingType == null && !rawInitializer.isBlank()) {
            bindingType = TypeRef.unspecified("java.lang.Object");
        }
        if (bindingType != null) {
            context.declareVariable(name, bindingType, !immutable);
        }
    }

    private boolean canDeclareJavaLocal(MethodContext context, String name) {
        return !context.variableTypes.containsKey(name);
    }

    private void reportJavaLocalShadow(CompilationUnit unit, org.antlr.v4.runtime.Token token, String name) {
        diagnostics.add(error(unit.sourceFile(), token.getLine(), token.getCharPositionInLine() + 1, name.length(),
                "AFFOGATO_DUPLICATE_LOCAL",
                "Duplicate local variable '" + name + "' shadows another local or parameter in the generated Java scope."));
    }

    private void checkExpressionStatement(CompilationUnit unit, AffogatoParser.StatementContext statement, MethodContext context) {
        String expression = renderServices.mergeTrailingClosure(
                renderServices.sourceText(unit.source(), statement.expressionStatement().expression()),
                unit.source(), statement.expressionStatement().trailingClosure(), context).trim();
        Matcher variableAssignment = VARIABLE_ASSIGNMENT.matcher(expression);
        if (variableAssignment.matches()) {
            validateVariableAssignment(variableAssignment, context, statement.getStart().getLine(), statement.getStart().getCharPositionInLine() + 1);
        }
        validateExpressionTyped(expression, context, statement.expressionStatement().expression());
    }

    private TypeGuess validateSwitchExpression(CompilationUnit unit, AffogatoParser.SwitchExpressionContext switchExpression,
                                            MethodContext context, TypeRef expectedType, String mismatchCode, String mismatchMessage) {
        TypedExpression typedCondition = validateExpressionTyped(renderServices.sourceText(unit.source(), switchExpression.condition()), context);
        validateSwitchSelector(typedCondition.resolvedType(), unit, context);
        TypeGuess inferredType = TypeGuess.unknown();
        for (AffogatoParser.SwitchArmContext arm : switchExpression.switchBody().switchArm()) {
            context.currentLine = arm.getStart().getLine();
            context.currentColumn = arm.getStart().getCharPositionInLine() + 1;
            if (arm.DEFAULT() == null) {
                for (AffogatoParser.SwitchLabelContext label : arm.switchLabel()) {
                    checkSwitchLabel(typedCondition.resolvedType(), label, unit, context);
                }
            }
            AffogatoParser.SwitchArmBodyContext body = arm.switchArmBody();
            if (body.block() != null) {
                diagnostics.add(error(unit.sourceFile(), context.currentLine, context.currentColumn,
                        "AFFOGATO_SWITCH_EXPR_BODY", "Switch expression arms must produce a value with '-> expression'; block arms are not supported."));
            } else {
                TypedExpression armExpr = validateExpressionTyped(renderServices.sourceText(unit.source(), body.expression()), context);
                if (expectedType != null && armExpr.resolvedType().isKnown() && !isAssignable(armExpr.resolvedType(), expectedType, context)) {
                    diagnostics.add(error(unit.sourceFile(), context.currentLine, context.currentColumn, mismatchCode, mismatchMessage));
                }
                inferredType = mergeSwitchArmType(inferredType, armExpr.resolvedType(), context);
            }
        }
        return inferredType;
    }

    TypedExpression validateExpressionTyped(String expression, MethodContext context) {
        return validateExpressionTyped(expression, context, null);
    }

    TypedExpression validateExpressionTyped(String expression, MethodContext context, ParserRuleContext expressionAnchor) {
        int savedExpressionLine = context.expressionLine;
        int savedExpressionColumn = context.expressionColumn;
        if (expressionAnchor != null && expressionAnchor.getStart() != null) {
            context.expressionLine = expressionAnchor.getStart().getLine();
            context.expressionColumn = expressionAnchor.getStart().getCharPositionInLine() + 1;
        } else {
            context.expressionLine = context.currentLine;
            context.expressionColumn = context.currentColumn;
        }
        try {
            AstExpression ast = expressionAst(expression, context);
            validateExpressionSubset(ast, context, expression);
            validateExpressionSemantics(ast, context, expression);
            TypeGuess resolvedType = ast.resolvedType().isKnown() && astTypeCanShortCircuitInference(ast)
                    ? ast.resolvedType()
                    : inferExpressionType(expression.trim(), context);
            return new TypedExpression("", resolvedType, ast);
        } finally {
            context.expressionLine = savedExpressionLine;
            context.expressionColumn = savedExpressionColumn;
        }
    }

    private TypeRef typeRef(AffogatoParser.TypeRefContext context) {
        return parserRunner.typeRef(context);
    }

    private int matchingBracket(String text, int open) {
        return parserRunner.matchingBracket(text, open);
    }

    private static String numericLiteralType(String literal) {
        return AffogatoParserRunner.numericLiteralType(literal);
    }

    AffogatoDiagnostic error(Path sourceFile, int line, int column, String code, String message) {
        return error(sourceFile, line, column, 1, code, message);
    }

    AffogatoDiagnostic error(Path sourceFile, int line, int column, int length, String code, String message) {
        return new AffogatoDiagnostic(AffogatoDiagnostic.Severity.ERROR, code, message, sourceFile, line, column, length);
    }

    AffogatoDiagnostic error(Path sourceFile, int line, int column, int length, String code, String message, String hint) {
        return new AffogatoDiagnostic(AffogatoDiagnostic.Severity.ERROR, code, message, sourceFile, line, column, length, hint);
    }


    void validateTypeRef(TypeRef type, CompilationUnit unit, int line, int column) {
        validateTypeName(type.javaType(), unit, line, column);
    }
    void validateTypeName(String typeName, CompilationUnit unit, int line, int column) {
        String javaType = stripNullableSuffix(typeName.trim());
        if (javaType.equals("void") || PRIMITIVES.contains(javaType) || javaType.equals("?")) {
            return;
        }
        String raw = javaType;
        while (raw.endsWith("[]")) {
            raw = raw.substring(0, raw.length() - 2);
        }
        int generic = raw.indexOf('<');
        if (generic >= 0) {
            int genericEnd = raw.lastIndexOf('>');
            if (genericEnd > generic) {
                for (String argument : splitTopLevel(raw.substring(generic + 1, genericEnd), ',')) {
                    validateTypeName(stripWildcardBound(argument), unit, line, column);
                }
            }
            raw = raw.substring(0, generic);
        }
        if (PRIMITIVES.contains(raw) || activeTypeParams.contains(raw) || symbols.lookupClass(raw, unit) != null || javaResolver.typeExists(raw, unit)) {
            return;
        }
        diagnostics.add(error(
                unit.sourceFile(),
                line,
                column,
                raw.length(),
                "AFFOGATO_TYPE_RESOLUTION",
                "Cannot resolve type " + raw + ".",
                spellingHint("type", raw, typeSuggestionCandidates(unit))
        ));
    }

    private String spellingHint(String kind, String name, List<String> candidates) {
        String suggestion = closestName(name, candidates);
        if (suggestion == null) {
            return null;
        }
        return "Did you mean '" + suggestion + "'?";
    }

    private String closestName(String name, List<String> candidates) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        String simpleName = simpleTypeName(name);
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String simpleCandidate = simpleTypeName(candidate);
            int distance = levenshtein(simpleName, simpleCandidate);
            int limit = Math.max(2, simpleName.length() / 3);
            if (distance <= limit && (distance < bestDistance
                    || distance == bestDistance && simpleCandidate.compareTo(best == null ? "" : best) < 0)) {
                bestDistance = distance;
                best = simpleCandidate;
            }
        }
        return best;
    }

    private int levenshtein(String left, String right) {
        String a = left == null ? "" : left;
        String b = right == null ? "" : right;
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int index = 0; index <= b.length(); index++) {
            previous[index] = index;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private List<String> typeSuggestionCandidates(CompilationUnit unit) {
        List<String> candidates = new ArrayList<>();
        candidates.addAll(classSymbols.typeNames());
        candidates.addAll(unit.imports().stream()
                .filter(importName -> !importName.endsWith(".*") && !importName.startsWith("static "))
                .toList());
        candidates.addAll(List.of(
                "String", "Object", "Boolean", "Byte", "Short", "Integer", "Long", "Float", "Double", "Character",
                "List", "Map", "Set", "Optional", "ArrayList", "HashMap", "HashSet", "Supplier", "Function"
        ));
        return candidates;
    }
    String stripWildcardBound(String typeName) {
        String type = typeName.trim();
        if (type.equals("?")) {
            return type;
        }
        if (type.startsWith("? extends ")) {
            return type.substring("? extends ".length()).trim();
        }
        if (type.startsWith("? super ")) {
            return type.substring("? super ".length()).trim();
        }
        return type;
    }
    void validateReturn(String rawExpression, MethodContext context, int line, int column) {
        validateReturn(rawExpression, context, line, column, line, column);
    }

    void validateReturn(String rawExpression, MethodContext context, int line, int column, int expressionLine, int expressionColumn) {
        boolean returnsVoid = context.returnType.javaType().equals("void");
        if (rawExpression.isBlank()) {
            if (!returnsVoid) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        line,
                        column,
                        "AFFOGATO_RETURN_TYPE",
                        "Method " + context.executableName + " must return " + context.returnType.javaType() + "."
                ));
            }
            return;
        }
        if (returnsVoid) {
            diagnostics.add(error(
                    context.unit.sourceFile(),
                    line,
                    column,
                    "AFFOGATO_RETURN_TYPE",
                    "Void method " + context.executableName + " cannot return a value."
            ));
            return;
        }
        withExpressionLocation(context, expressionLine, expressionColumn, () -> validateAssignment(
                    context.returnType,
                    rawExpression,
                    context,
                    line,
                    column,
                    "AFFOGATO_RETURN_TYPE",
                    "Returned value is not assignable to " + context.returnType.javaType() + "."
            ));
    }

    private void withExpressionLocation(MethodContext context, int line, int column, Runnable action) {
        int savedExpressionLine = context.expressionLine;
        int savedExpressionColumn = context.expressionColumn;
        context.expressionLine = line;
        context.expressionColumn = column;
        try {
            action.run();
        } finally {
            context.expressionLine = savedExpressionLine;
            context.expressionColumn = savedExpressionColumn;
        }
    }
    void validateThrowExpression(TypedExpression expression, MethodContext context, int line, int column) {
        TypeGuess type = expression.resolvedType();
        if (type.isKnown() && !type.isNullLiteral() && !context.javaResolver.throwableCompatible(type, context.unit)) {
            diagnostics.add(error(
                    context.unit.sourceFile(),
                    line,
                    column,
                    "AFFOGATO_THROW_TYPE",
                    "Throw expressions must be Throwable."
            ));
        }
    }
    void validateVariableAssignment(Matcher matcher, MethodContext context, int line, int column) {
        String name = matcher.group(1);
        String expectedType = context.variableTypes.get(name);
        if (expectedType == null) {
            return;
        }
        if (Boolean.FALSE.equals(context.mutableVariables.get(name))) {
            diagnostics.add(error(
                    context.unit.sourceFile(),
                    line,
                    column,
                    "AFFOGATO_LET_ASSIGN",
                    "Cannot assign to let local " + name + "."
            ));
            return;
        }
        validateAssignment(
                new TypeRef(expectedType, context.variableNullabilities.getOrDefault(name, Nullability.UNSPECIFIED)),
                matcher.group(2),
                context,
                line,
                column,
                "AFFOGATO_ASSIGNMENT_TYPE",
                "Assigned value is not assignable to " + expectedType + "."
        );
    }
    void validateCondition(String rawExpression, MethodContext context, int line, int column) {
        AstExpression ast = expressionAst(rawExpression, context);
        TypeGuess type = ast.resolvedType().isKnown() ? ast.resolvedType() : inferExpressionType(rawExpression, context);
        if ((type.isKnown() && !isBooleanType(type)) || !isBooleanConditionAst(ast, context)) {
            diagnostics.add(error(
                    context.unit.sourceFile(),
                    line,
                    column,
                    "AFFOGATO_CONDITION_TYPE",
                    "Conditions must be boolean."
            ));
        }
    }
    void validateAssignment(
            TypeRef expected,
            String rawExpression,
            MethodContext context,
            int line,
            int column,
            String code,
            String message
    ) {
        if (narrowConstantAssignable(rawExpression, expected)) {
            // JLS 5.2: an int constant in range assigns to byte/short/char without a cast.
            return;
        }
        AstExpression ast = expressionAst(rawExpression, context);
        validateExpressionSemantics(ast, context, rawExpression);
        if (!isAssignmentAstCompatible(ast, expected, context)) {
            diagnostics.add(error(context.unit.sourceFile(), line, column, code, message));
            return;
        }
        TypeGuess actual = inferExpressionType(rawExpression, context, TypeGuess.of(expected.javaType()));
        if (!actual.isKnown()) {
            return;
        }
        if (!isAssignable(actual, expected, context)) {
            diagnostics.add(error(context.unit.sourceFile(), line, column, code, message));
        }
    }
    boolean narrowConstantAssignable(String rawExpression, TypeRef expected) {
        long min;
        long max;
        switch (expected.javaType()) {
            case "byte" -> { min = Byte.MIN_VALUE; max = Byte.MAX_VALUE; }
            case "short" -> { min = Short.MIN_VALUE; max = Short.MAX_VALUE; }
            case "char" -> { min = Character.MIN_VALUE; max = Character.MAX_VALUE; }
            default -> { return false; }
        }
        String text = rawExpression.trim();
        boolean negative = text.startsWith("-");
        if (negative) {
            text = text.substring(1).trim();
        }
        if (text.isEmpty()) {
            return false;
        }
        char last = text.charAt(text.length() - 1);
        if (last == 'l' || last == 'L') {
            return false; // long constants do not narrow
        }
        boolean hex = text.length() > 2 && text.charAt(0) == '0' && (text.charAt(1) == 'x' || text.charAt(1) == 'X');
        String digits = (hex ? text.substring(2) : text).replace("_", "");
        int radix = hex ? 16 : 10;
        if (digits.isEmpty() || digits.chars().anyMatch(ch -> Character.digit(ch, radix) < 0)) {
            return false;
        }
        BigInteger value;
        try {
            value = new BigInteger(digits, radix);
        } catch (NumberFormatException malformed) {
            return false;
        }
        if (negative) {
            value = value.negate();
        }
        return value.compareTo(BigInteger.valueOf(min)) >= 0 && value.compareTo(BigInteger.valueOf(max)) <= 0;
    }
    boolean isAssignmentAstCompatible(AstExpression ast, TypeRef expected, MethodContext context) {
        if (ast instanceof TernaryExpression ternary) {
            return isBooleanConditionAst(ternary.condition(), context)
                    && isAssignmentAstCompatible(ternary.thenExpression(), expected, context)
                    && isAssignmentAstCompatible(ternary.elseExpression(), expected, context);
        }
        if (expected.nullability() == Nullability.NOT_NULL && expressionMayBeNullable(ast, context)) {
            return false;
        }
        TypeGuess actual = ast.resolvedType().isKnown() ? ast.resolvedType() : inferExpressionType(ast.source(), context);
        return !actual.isKnown() || isAssignable(actual, expected, context);
    }
    boolean expressionMayBeNullable(AstExpression ast, MethodContext context) {
        if (ast instanceof LiteralExpression literal && literal.resolvedType().isNullLiteral()) {
            return true;
        }
        if (ast instanceof IdentifierExpression identifier) {
            return context.variableNullabilities.getOrDefault(identifier.name(), Nullability.UNSPECIFIED) == Nullability.NULLABLE;
        }
        if (ast instanceof TernaryExpression ternary) {
            return expressionMayBeNullable(ternary.thenExpression(), context)
                    || expressionMayBeNullable(ternary.elseExpression(), context);
        }
        if (ast instanceof CastExpression cast) {
            return expressionMayBeNullable(cast.expression(), context);
        }
        return false;
    }
    boolean isBooleanConditionAst(AstExpression ast, MethodContext context) {
        if (ast instanceof TernaryExpression ternary) {
            return isBooleanConditionAst(ternary.condition(), context)
                    && isBooleanOperand(ternary.thenExpression(), context)
                    && isBooleanOperand(ternary.elseExpression(), context);
        }
        if (ast instanceof BinaryExpression binary) {
            return switch (binary.operator()) {
                case "||", "&&" -> isBooleanOperand(binary.left(), context) && isBooleanOperand(binary.right(), context);
                case "<", "<=", ">", ">=" -> isNumericOperand(binary.left(), context) && isNumericOperand(binary.right(), context);
                case "==", "!=" -> true;
                default -> {
                    TypeGuess type = inferExpressionType(binary.source(), context);
                    yield !type.isKnown() || isBooleanType(type);
                }
            };
        }
        if (ast instanceof UnaryExpression unary && unary.operator().equals("!")) {
            return isBooleanOperand(unary.expression(), context);
        }
        TypeGuess type = ast.resolvedType().isKnown() ? ast.resolvedType() : inferExpressionType(ast.source(), context);
        return !type.isKnown() || isBooleanType(type);
    }
    boolean isBooleanOperand(AstExpression ast, MethodContext context) {
        return isBooleanConditionAst(ast, context);
    }
    boolean isNumericOperand(AstExpression ast, MethodContext context) {
        TypeGuess type = ast.resolvedType().isKnown() ? ast.resolvedType() : inferExpressionType(ast.source(), context);
        return !type.isKnown() || isNumericType(type);
    }
    boolean isPlusOperandCompatible(AstExpression left, AstExpression right, MethodContext context) {
        TypeGuess leftType = left.resolvedType().isKnown() ? left.resolvedType() : inferExpressionType(left.source(), context);
        TypeGuess rightType = right.resolvedType().isKnown() ? right.resolvedType() : inferExpressionType(right.source(), context);
        if (!leftType.isKnown() || !rightType.isKnown()) {
            return true;
        }
        return isStringType(leftType) || isStringType(rightType) || isNumericType(leftType) && isNumericType(rightType);
    }
    boolean isEqualityCompatible(AstExpression left, AstExpression right, MethodContext context) {
        TypeGuess leftType = left.resolvedType().isKnown() ? left.resolvedType() : inferExpressionType(left.source(), context);
        TypeGuess rightType = right.resolvedType().isKnown() ? right.resolvedType() : inferExpressionType(right.source(), context);
        if (!leftType.isKnown() || !rightType.isKnown() || leftType.isNullLiteral() || rightType.isNullLiteral()) {
            return true;
        }
        if (isBooleanType(leftType) || isBooleanType(rightType)) {
            return isBooleanType(leftType) && isBooleanType(rightType);
        }
        if (isNumericType(leftType) || isNumericType(rightType)) {
            return isNumericType(leftType) && isNumericType(rightType);
        }
        return context.javaResolver.assignmentCompatible(leftType, rightType.javaType(), context.unit, InvocationPhase.LOOSE)
                || context.javaResolver.assignmentCompatible(rightType, leftType.javaType(), context.unit, InvocationPhase.LOOSE);
    }
    boolean ternaryBranchesCompatible(AstExpression thenExpression, AstExpression elseExpression, MethodContext context) {
        TypeGuess thenType = thenExpression.resolvedType().isKnown() ? thenExpression.resolvedType() : inferExpressionType(thenExpression.source(), context);
        TypeGuess elseType = elseExpression.resolvedType().isKnown() ? elseExpression.resolvedType() : inferExpressionType(elseExpression.source(), context);
        if (!thenType.isKnown() || !elseType.isKnown() || thenType.isNullLiteral() || elseType.isNullLiteral()) {
            return true;
        }
        if (thenType.javaType().equals(elseType.javaType())) {
            return true;
        }
        if (isNumericType(thenType) && isNumericType(elseType)) {
            return true;
        }
        return context.javaResolver.assignmentCompatible(thenType, elseType.javaType(), context.unit, InvocationPhase.LOOSE)
                || context.javaResolver.assignmentCompatible(elseType, thenType.javaType(), context.unit, InvocationPhase.LOOSE);
    }
    boolean isBooleanType(TypeGuess type) {
        return type.javaType().equals("boolean") || type.javaType().equals("java.lang.Boolean")
                || type.javaType().equals("Boolean");
    }
    boolean isArrayIndexType(TypeGuess type) {
        return switch (primitiveNumericType(type.javaType())) {
            case "byte", "short", "char", "int" -> true;
            default -> false;
        };
    }
    boolean isAssignable(TypeGuess actual, TypeRef expected, MethodContext context) {
        if (actual.isNullLiteral() && expected.nullability() == Nullability.NOT_NULL) {
            return false;
        }
        if (!actual.isKnown()) {
            return true;
        }
        return context.javaResolver.assignmentCompatible(actual, expected.javaType(), context.unit, InvocationPhase.LOOSE);
    }
    void expressionSemanticError(MethodContext context, String rawExpression, AstExpression at, String code, String message) {
        int line = context.expressionLine > 0 ? context.expressionLine : context.currentLine;
        int baseColumn = expressionBaseColumn(context, rawExpression);
        int offset = AstSpans.startOffset(at);
        String nodeSource = at.source() == null ? "" : at.source().trim();
        String raw = rawExpression == null ? "" : rawExpression;
        if (!nodeSource.isBlank() && !raw.trim().equals(nodeSource)) {
            int located = raw.lastIndexOf(nodeSource);
            if (located >= 0) {
                offset = located;
            }
        }
        int length = AstSpans.spanLength(at, offset);
        diagnostics.add(error(context.unit.sourceFile(), line, baseColumn + offset, length, code, message));
    }
    static int expressionBaseColumn(MethodContext context, String rawExpression) {
        int base = context.expressionColumn > 0 ? context.expressionColumn : context.currentColumn;
        int index = 0;
        while (index < rawExpression.length() && Character.isWhitespace(rawExpression.charAt(index))) {
            index++;
        }
        return base + index;
    }
    void validateExpressionSubset(AstExpression ast, MethodContext context, String rawExpression) {
        if (ast instanceof UnsupportedExpression unsupported) {
            expressionSemanticError(context, rawExpression, unsupported, unsupported.code(), unsupported.message());
        }
    }
    void validateExpressionSemantics(AstExpression ast, MethodContext context, String rawExpression) {
        if (ast instanceof NamedArgumentExpression named) {
            validateExpressionSemantics(named.expression(), context, rawExpression);
            return;
        }
        if (ast instanceof SafeCallExpression safeCall) {
            validateExpressionSemantics(safeCall.receiver(), context, rawExpression);
            return;
        }
        if (ast instanceof ElvisExpression elvis) {
            validateExpressionSemantics(elvis.left(), context, rawExpression);
            validateExpressionSemantics(elvis.right(), context, rawExpression);
            return;
        }
        if (ast instanceof BinaryExpression binary) {
            validateExpressionSemantics(binary.left(), context, rawExpression);
            validateExpressionSemantics(binary.right(), context, rawExpression);
            if ((binary.operator().equals("||") || binary.operator().equals("&&"))
                    && (!isBooleanOperand(binary.left(), context) || !isBooleanOperand(binary.right(), context))) {
                expressionSemanticError(context, rawExpression, binary, "AFFOGATO_CONDITION_TYPE",
                        "Boolean operators require boolean operands.");
            } else if (List.of("<", "<=", ">", ">=").contains(binary.operator())
                    && (!isNumericOperand(binary.left(), context) || !isNumericOperand(binary.right(), context))) {
                expressionSemanticError(context, rawExpression, binary, "AFFOGATO_CONDITION_TYPE",
                        "Relational operators require numeric operands.");
            } else if (List.of("-", "*", "/", "%").contains(binary.operator())
                    && (!isNumericOperand(binary.left(), context) || !isNumericOperand(binary.right(), context))) {
                expressionSemanticError(context, rawExpression, binary, "AFFOGATO_OPERATOR_TYPE",
                        "Arithmetic operators require numeric operands.");
            } else if (binary.operator().equals("+") && !isPlusOperandCompatible(binary.left(), binary.right(), context)) {
                expressionSemanticError(context, rawExpression, binary, "AFFOGATO_OPERATOR_TYPE",
                        "Plus operands must be numeric or include a String operand.");
            } else if ((binary.operator().equals("==") || binary.operator().equals("!="))
                    && !isEqualityCompatible(binary.left(), binary.right(), context)) {
                expressionSemanticError(context, rawExpression, binary.right(), "AFFOGATO_OPERATOR_TYPE",
                        "Equality operands are not comparable.");
            } else if (List.of("&", "|", "^").contains(binary.operator())
                    && (!isNumericOperand(binary.left(), context) || !isNumericOperand(binary.right(), context))) {
                expressionSemanticError(context, rawExpression, binary, "AFFOGATO_OPERATOR_TYPE",
                        "Bitwise operators require numeric operands.");
            } else if (List.of("<<", ">>", ">>>").contains(binary.operator())
                    && (!isNumericOperand(binary.left(), context) || !isNumericOperand(binary.right(), context))) {
                expressionSemanticError(context, rawExpression, binary, "AFFOGATO_OPERATOR_TYPE",
                        "Shift operators require numeric operands.");
            }
            return;
        }
        if (ast instanceof UnaryExpression unary) {
            validateExpressionSemantics(unary.expression(), context, rawExpression);
            if (unary.operator().equals("!") && !isBooleanOperand(unary.expression(), context)) {
                expressionSemanticError(context, rawExpression, unary, "AFFOGATO_CONDITION_TYPE",
                        "Boolean negation requires a boolean operand.");
            } else if (unary.operator().equals("~") && !isNumericOperand(unary.expression(), context)) {
                expressionSemanticError(context, rawExpression, unary, "AFFOGATO_OPERATOR_TYPE",
                        "Bitwise complement requires a numeric operand.");
            } else if ((unary.operator().equals("++") || unary.operator().equals("--"))
                    && !isNumericOperand(unary.expression(), context)) {
                expressionSemanticError(context, rawExpression, unary, "AFFOGATO_OPERATOR_TYPE",
                        "Increment and decrement require a numeric operand.");
            } else if ((unary.operator().equals("++") || unary.operator().equals("--"))
                    && unary.expression() instanceof PropertyAccessExpression property
                    && isGetterSetterBackedPropertyAccess(property, context)) {
                expressionSemanticError(context, rawExpression, property, "AFFOGATO_PROPERTY_MUTATION_EXPR",
                        "Mutating property `" + property.source() + "` with `++`/`--`/`+=` is not supported inside an expression; do it in a separate statement.");
            }
            return;
        }
        if (ast instanceof TernaryExpression ternary) {
            validateExpressionSemantics(ternary.condition(), context, rawExpression);
            validateExpressionSemantics(ternary.thenExpression(), context, rawExpression);
            validateExpressionSemantics(ternary.elseExpression(), context, rawExpression);
            if (!isBooleanConditionAst(ternary.condition(), context)) {
                expressionSemanticError(context, rawExpression, ternary.condition(), "AFFOGATO_CONDITION_TYPE",
                        "Ternary conditions must be boolean.");
            }
            if (!ternaryBranchesCompatible(ternary.thenExpression(), ternary.elseExpression(), context)) {
                expressionSemanticError(context, rawExpression, ternary, "AFFOGATO_TERNARY_TYPE",
                        "Ternary branches must have compatible types.");
            }
            return;
        }
        if (ast instanceof InstanceOfExpression instanceOf) {
            validateExpressionSemantics(instanceOf.expression(), context, rawExpression);
            TypeGuess source = instanceOf.expression().resolvedType().isKnown()
                    ? instanceOf.expression().resolvedType()
                    : inferExpressionType(instanceOf.expression().source(), context);
            if (symbols.lookupClass(instanceOf.targetType(), context.unit) == null
                    && !context.javaResolver.typeExists(instanceOf.targetType(), context.unit)) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_TYPE_RESOLUTION",
                        "Cannot resolve type " + instanceOf.targetType() + "."
                ));
            }
            if (source.isKnown() && PRIMITIVES.contains(primitiveNumericType(source.javaType()))) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_INSTANCEOF_TYPE",
                        "Instance-of source must be a reference type."
                ));
            }
            return;
        }
        if (ast instanceof ClassLiteralExpression classLiteral) {
            String typeName = stripNullableSuffix(classLiteral.typeName());
            if (activeTypeParams.contains(typeName)) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_CLASS_LITERAL_TYPE",
                        "Class literals cannot use erased type parameter " + typeName + "."
                ));
            } else if (symbols.lookupClass(typeName, context.unit) == null
                    && !context.javaResolver.typeExists(typeName, context.unit)) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_TYPE_RESOLUTION",
                        "Cannot resolve type " + typeName + "."
                ));
            }
            return;
        }
        if (ast instanceof CallExpression call) {
            call.arguments().forEach(argument -> validateExpressionSemantics(argument, context, rawExpression));
            validateExpressionSemantics(call.receiver(), context, rawExpression);

            List<TypedArgument> typedArgs = new ArrayList<>();
            boolean hasNamed = false;
            for (AstExpression arg : call.arguments()) {
                if (arg instanceof NamedArgumentExpression named) {
                    hasNamed = true;
                    String valStr = new ExpressionRenderer(renderServices).render(named.expression(), context);
                    TypeGuess valType = named.expression().resolvedType().isKnown() ? named.expression().resolvedType() : inferExpressionType(valStr, context);
                    typedArgs.add(new TypedArgument(named.name(), valStr, valType, named.expression()));
                } else {
                    String valStr = new ExpressionRenderer(renderServices).render(arg, context);
                    TypeGuess valType = arg.resolvedType().isKnown() ? arg.resolvedType() : inferExpressionType(valStr, context);
                    typedArgs.add(new TypedArgument("", valStr, valType, arg));
                }
            }

            if (hasNamed) {
                Optional<ResolvedArguments> resolved = context.resolveArguments(call.name(), typedArgs);
                if (!resolved.isPresent()) {
                    Optional<String> assignmentTarget = typedArgs.stream()
                            .map(TypedArgument::name)
                            .filter(name -> !name.isBlank() && context.variableTypes.containsKey(name))
                            .findFirst();
                    if (assignmentTarget.isPresent()) {
                        String name = assignmentTarget.get();
                        diagnostics.add(error(
                                context.unit.sourceFile(),
                                context.currentLine,
                                context.currentColumn,
                                "AFFOGATO_ASSIGNMENT_ARGUMENT",
                                "'" + name + "' is a variable in scope, so '" + name + " = ...' is read as a named "
                                        + "argument to " + call.name() + ", not an assignment. Assignment expressions are not "
                                        + "allowed as call arguments; assign in a separate statement before the call."
                        ));
                    } else {
                        String failure = context.resolutionFailure();
                        diagnostics.add(error(
                                context.unit.sourceFile(),
                                context.currentLine,
                                context.currentColumn,
                                "AFFOGATO_NAMED_ARGS",
                                failure.isBlank()
                                        ? "Cannot resolve named arguments for call " + call.name() + ". Compile Java dependencies with -parameters or use a Affogato declaration."
                                        : failure
                        ));
                    }
                }
            }

            String simpleName = call.name().contains(".") ? call.name().substring(call.name().lastIndexOf('.') + 1) : call.name();
            boolean isExtension = false;
            if (call.receiver() != null && !(call.receiver() instanceof UnknownExpression)) {
                String receiverText = new ExpressionRenderer(renderServices).render(call.receiver(), context);
                TypeGuess receiverType = call.receiver().resolvedType().isKnown() ? call.receiver().resolvedType() : inferExpressionType(receiverText, context);
                if (receiverType.isKnown()) {
                    String rawOwner = receiverType.javaType();
                    String resolvedOwner = context.activeTypeParams.contains(rawOwner) ? "java.lang.Object" : rawOwner;
                    Optional<ExtensionMatch> match = context.dispatchExtension(simpleTypeName(resolvedOwner), simpleName, typedArgs);
                    if (match.isPresent()) {
                        isExtension = true;
                    }
                }
            }

            if (!isExtension) {
                if (call.receiver() != null && !(call.receiver() instanceof UnknownExpression)) {
                    String receiverText = new ExpressionRenderer(renderServices).render(call.receiver(), context);
                    TypeGuess receiverType = call.receiver().resolvedType().isKnown() ? call.receiver().resolvedType() : inferExpressionType(receiverText, context);
                    if (receiverType.isKnown()) {
                        TypeGuess returnType = context.returnTypeForReceiverType(receiverType.javaType(), simpleName, typedArgs);
                        if (!returnType.isKnown()) {
                            diagnostics.add(error(
                                    context.unit.sourceFile(),
                                    context.currentLine,
                                    context.currentColumn,
                                    "AFFOGATO_CALL_RESOLUTION",
                                    "Cannot resolve call " + simpleName + " on " + receiverType.javaType() + "."
                            ));
                        }
                    }
                } else {
                    int openIndex = call.source().indexOf('(');
                    if (openIndex < call.name().length()) {
                        openIndex = call.name().length();
                    }
                    if (shouldValidateCall(call.name(), call.source(), openIndex, context)) {
                        TypeGuess returnType = context.returnType(call.name(), typedArgs);
                        if (!returnType.isKnown()) {
                            diagnostics.add(error(
                                    context.unit.sourceFile(),
                                    context.currentLine,
                                    context.currentColumn,
                                    "AFFOGATO_CALL_RESOLUTION",
                                    "Cannot resolve call " + call.name() + "."
                            ));
                        }
                    }
                }
            }
            return;
        }
        if (ast instanceof ConstructorExpression constructor) {
            constructor.arguments().forEach(argument -> validateExpressionSemantics(argument, context, rawExpression));

            List<TypedArgument> typedArgs = new ArrayList<>();
            boolean hasNamed = false;
            for (AstExpression arg : constructor.arguments()) {
                if (arg instanceof NamedArgumentExpression named) {
                    hasNamed = true;
                    String valStr = new ExpressionRenderer(renderServices).render(named.expression(), context);
                    TypeGuess valType = named.expression().resolvedType().isKnown() ? named.expression().resolvedType() : inferExpressionType(valStr, context);
                    typedArgs.add(new TypedArgument(named.name(), valStr, valType, named.expression()));
                } else {
                    String valStr = new ExpressionRenderer(renderServices).render(arg, context);
                    TypeGuess valType = arg.resolvedType().isKnown() ? arg.resolvedType() : inferExpressionType(valStr, context);
                    typedArgs.add(new TypedArgument("", valStr, valType, arg));
                }
            }

            String displayType = constructor.typeName();
            String impl = constructorImplementation(displayType);
            
            if (symbols.lookupClass(displayType, context.unit) == null && !context.javaResolver.typeExists(impl, context.unit)) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_TYPE_RESOLUTION",
                        "Cannot resolve type " + displayType + "."
                ));
                return;
            }

            ClassSymbol affogatoTarget = symbols.lookupClass(displayType, context.unit);
            boolean resolvedOk = false;
            boolean isAmbiguous = false;
            if (affogatoTarget != null) {
                Optional<ResolvedArguments> resolved = context.resolveArguments(displayType, typedArgs);
                if (resolved.isPresent()) {
                    resolvedOk = true;
                }
            } else {
                Optional<ResolvedArguments> resolved = context.javaResolver.resolveConstructorArguments(impl, typedArgs, context.unit);
                if (resolved.isPresent()) {
                    resolvedOk = true;
                }
                if (context.javaResolver.lastResolutionAmbiguous()) {
                    isAmbiguous = true;
                }
            }

            if (!resolvedOk) {
                if (isAmbiguous) {
                    diagnostics.add(error(
                            context.unit.sourceFile(),
                            context.currentLine,
                            context.currentColumn,
                            "AFFOGATO_CONSTRUCTOR_RESOLUTION",
                            "Ambiguous overload for constructor " + displayType + "."
                    ));
                } else {
                    String failure = context.resolutionFailure();
                    diagnostics.add(error(
                            context.unit.sourceFile(),
                            context.currentLine,
                            context.currentColumn,
                            "AFFOGATO_CONSTRUCTOR_RESOLUTION",
                            failure.isBlank()
                                    ? "Cannot resolve constructor " + displayType + "."
                                    : failure.replace("call " + impl, "constructor " + displayType)
                    ));
                }
            }
            return;
        }
        if (ast instanceof AssignmentExpression assignment) {
            validateExpressionSemantics(assignment.target(), context, rawExpression);
            validateExpressionSemantics(assignment.value(), context, rawExpression);
            if (isCompoundAssignmentSource(assignment.source())
                    && assignment.target() instanceof PropertyAccessExpression property
                    && isGetterSetterBackedPropertyAccess(property, context)) {
                expressionSemanticError(context, rawExpression, property, "AFFOGATO_PROPERTY_MUTATION_EXPR",
                        "Mutating property `" + property.source() + "` with `++`/`--`/`+=` is not supported inside an expression; do it in a separate statement.");
            }
            return;
        }
        if (ast instanceof CastExpression cast) {
            validateExpressionSemantics(cast.expression(), context, rawExpression);
            TypeGuess source = cast.expression().resolvedType().isKnown()
                    ? cast.expression().resolvedType()
                    : inferExpressionType(cast.expression().source(), context);
            if (symbols.lookupClass(cast.targetType(), context.unit) == null
                    && !context.javaResolver.typeExists(cast.targetType(), context.unit)) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_TYPE_RESOLUTION",
                        "Cannot resolve type " + cast.targetType() + "."
                ));
            }
            if (source.isKnown()
                    && !source.isNullLiteral()
                    && !source.isLambda()
                    && !context.javaResolver.castPossible(source, cast.targetType(), context.unit)) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_CAST_TYPE",
                        "Cannot cast " + source.javaType() + " to " + cast.targetType() + "."
                ));
            }
            return;
        }
        if (ast instanceof PropertyAccessExpression property) {
            validateExpressionSemantics(property.receiver(), context, rawExpression);
            TypeGuess receiverType = property.receiver().resolvedType().isKnown()
                    ? property.receiver().resolvedType()
                    : inferExpressionType(property.receiver().source(), context);
            // Resolve on the receiver type from the AST receiver, so a call/cast/paren receiver
            // (`make().name`, `(o as T).name`) is checked instead of being rejected because the flat
            // source contains parentheses.
            TypeGuess resolved = propertyType(receiverType, property.property(), context);
            if (receiverType.isKnown() && !receiverType.isNullLiteral() && !resolved.isKnown()) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_PROPERTY_RESOLUTION",
                        "Cannot resolve property " + property.property() + " on " + receiverType.javaType() + "."
                ));
            }
            return;
        }
        if (ast instanceof ArrayLiteralExpression arrayLiteral) {
            arrayLiteral.elements().forEach(element -> validateExpressionSemantics(element, context, rawExpression));
            return;
        }
        if (ast instanceof ArrayAccessExpression arrayAccess) {
            validateExpressionSemantics(arrayAccess.receiver(), context, rawExpression);
            validateExpressionSemantics(arrayAccess.index(), context, rawExpression);
            TypeGuess receiverType = arrayAccess.receiver().resolvedType().isKnown()
                    ? arrayAccess.receiver().resolvedType()
                    : inferExpressionType(arrayAccess.receiver().source(), context);
            TypeGuess indexType = arrayAccess.index().resolvedType().isKnown()
                    ? arrayAccess.index().resolvedType()
                    : inferExpressionType(arrayAccess.index().source(), context);
            if (receiverType.isKnown() && !receiverType.isNullLiteral() && !receiverType.javaType().endsWith("[]")) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_ARRAY_ACCESS_TYPE",
                        "Array access requires an array receiver."
                ));
            }
            if (indexType.isKnown() && !isArrayIndexType(indexType)) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_ARRAY_INDEX_TYPE",
                        "Array indexes must be int-compatible."
                ));
            }
            return;
        }
        if (ast instanceof IdentifierExpression identifier && !identifier.resolvedType().isKnown()) {
            if (!identifier.name().equals("this")
                    && !identifier.name().equals("super")
                    && context.isLocalDeclaredLaterInBlock(identifier.name())) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        SourceLocations.columnOfIdentifier(
                                context.unit.source(),
                                context.currentLine,
                                identifier.name(),
                                context.currentColumn),
                        identifier.name().length(),
                        "AFFOGATO_USE_BEFORE_INIT",
                        "Variable '" + identifier.name() + "' is used before it is declared."
                ));
            } else if (!identifier.name().equals("this")
                    && !identifier.name().equals("super")
                    && !context.identifierResolvesAsMember(identifier.name())
                    && symbols.lookupClass(identifier.name(), context.unit) == null
                    && !context.javaResolver.typeExists(identifier.name(), context.unit)) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        SourceLocations.columnOfIdentifier(
                                context.unit.source(),
                                context.currentLine,
                                identifier.name(),
                                context.currentColumn),
                        identifier.name().length(),
                        "AFFOGATO_IDENTIFIER_RESOLUTION",
                        "Cannot resolve identifier " + identifier.name() + ".",
                        spellingHint("identifier", identifier.name(), identifierSuggestionCandidates(context))
                ));
            }
        }
    }

    private List<String> identifierSuggestionCandidates(MethodContext context) {
        List<String> candidates = new ArrayList<>(context.variableTypes.keySet());
        candidates.addAll(context.currentClass.fields().stream().map(FieldDecl::name).toList());
        candidates.addAll(context.currentClass.methods().stream().map(MethodDecl::name).toList());
        return candidates;
    }
    void validateSwitchLabel(TypeGuess selectorType, TypeGuess labelType, CompilationUnit unit, MethodContext context) {
        if (!selectorType.isKnown() || !labelType.isKnown() || selectorType.isNullLiteral() || labelType.isNullLiteral()) {
            return;
        }
        if (!context.javaResolver.assignmentCompatible(labelType, selectorType.javaType(), unit, InvocationPhase.LOOSE)) {
            diagnostics.add(error(
                    unit.sourceFile(),
                    context.currentLine,
                    context.currentColumn,
                    "AFFOGATO_SWITCH_LABEL_TYPE",
                    "Switch case label is not compatible with " + selectorType.javaType() + "."
            ));
        }
    }
    void validateSwitchSelector(TypeGuess selectorType, CompilationUnit unit, MethodContext context) {
        if (!selectorType.isKnown() || selectorType.isNullLiteral()) {
            return;
        }
        if (!context.javaResolver.switchSelectorCompatible(selectorType, unit)) {
            diagnostics.add(error(
                    unit.sourceFile(),
                    context.currentLine,
                    context.currentColumn,
                    "AFFOGATO_SWITCH_SELECTOR_TYPE",
                    "Switch selectors must be String, enum, or an int-compatible type."
            ));
        }
    }
    TypeGuess mergeSwitchArmType(TypeGuess current, TypeGuess next, MethodContext context) {
        if (!next.isKnown() || next.isNullLiteral()) {
            return current;
        }
        if (!current.isKnown() || current.isNullLiteral()) {
            return next;
        }
        if (current.javaType().equals(next.javaType())) {
            return current;
        }
        if (isNumericType(current) && isNumericType(next)) {
            return TypeGuess.of(promotedNumericType(current.javaType(), next.javaType()));
        }
        if (context.javaResolver.assignmentCompatible(next, current.javaType(), context.unit, InvocationPhase.LOOSE)) {
            return current;
        }
        if (context.javaResolver.assignmentCompatible(current, next.javaType(), context.unit, InvocationPhase.LOOSE)) {
            return next;
        }
        return TypeGuess.unknown();
    }
    TypeGuess inferExpressionType(String expression, MethodContext context) {
        return inferExpressionType(expression, context, TypeGuess.unknown());
    }
    TypeGuess inferExpressionType(String expression, MethodContext context, TypeGuess expected) {
        if (expression == null || expression.isBlank()) {
            return TypeGuess.unknown();
        }
        AstExpression ast = expressionAst(expression, context);
        if (ast.resolvedType().isKnown() && astTypeCanShortCircuitInference(ast)) {
            return ast.resolvedType();
        }
        String value = stripOuterParens(expression.trim());
        if (value.isBlank()) {
            return TypeGuess.unknown();
        }
        int arrowIndex = topLevelOperatorIndex(value, List.of("->"));
        if (arrowIndex >= 0) {
            if (expected.isLambda() && expected.isKnown()) {
                return expected;
            }
            return TypeGuess.lambda(lambdaParameterArity(value.substring(0, arrowIndex)));
        }
        if (containsTopLevelMethodReference(value)) {
            if (expected.isLambda() && expected.isKnown()) {
                return expected;
            }
            return TypeGuess.lambda();
        }
        if (value.equals("null")) {
            return expected.isKnown() ? expected : TypeGuess.nullLiteral();
        }
        if (value.startsWith("\"") && stringLiteralEnd(value, 0) == value.length()) {
            return TypeGuess.of("String");
        }
        if (value.equals("true") || value.equals("false")) {
            return TypeGuess.of("boolean");
        }
        String numericType = numericLiteralType(value);
        if (numericType != null) {
            return TypeGuess.of(numericType);
        }

        // Array literal `[e1, e2, ...]`
        if (value.startsWith("[") && value.endsWith("]") && matchingBracket(value, 0) == value.length() - 1) {
            String inner = value.substring(1, value.length() - 1).trim();
            if (!inner.isBlank()) {
                List<String> elements = splitTopLevel(inner, ',').stream().map(String::trim).toList();
                String baseType = expected.isKnown() && expected.javaType().endsWith("[]") 
                    ? expected.javaType().substring(0, expected.javaType().length() - 2)
                    : inferArrayElementType(elements, context);
                return TypeGuess.of(baseType + "[]");
            }
        }

        // Array/list subscript `receiver[index]` — infer the element type of the receiver so a property
        // read on an element (`ps[0].name`) resolves its accessor instead of leaking a raw field read.
        if (value.endsWith("]")) {
            int open = matchBackward(new StringBuilder(value), value.length() - 1, '[', ']');
            if (open > 0) {
                Optional<TypeGuess> element = elementType(inferExpressionType(value.substring(0, open), context));
                if (element.isPresent()) {
                    return element.get();
                }
            }
        }

        Matcher classLiteral = Pattern.compile("^([A-Za-z_$][A-Za-z0-9_.$]*(?:<[^>]+>)?(?:\\[\\])*)\\.class$").matcher(value);
        if (classLiteral.matches()) {
            return TypeGuess.of("java.lang.Class");
        }

        Matcher affogatoCast = Pattern.compile("^.+\\s+as\\s+([A-Za-z_][A-Za-z0-9_.$]*(?:<[^>]+>)?\\??)$").matcher(value);
        if (affogatoCast.matches()) {
            return TypeGuess.of(stripNullableSuffix(affogatoCast.group(1)));
        }
        Matcher javaCast = Pattern.compile("^\\(\\(([^)]+)\\)\\s+.+\\)$").matcher(value);
        if (javaCast.matches()) {
            return TypeGuess.of(stripNullableSuffix(javaCast.group(1).trim()));
        }

        String knownVariableType = context.variableTypes.get(value);
        if (knownVariableType != null) {
            return TypeGuess.of(knownVariableType);
        }

        Matcher newExpression = Pattern.compile("^new\\s+([A-Za-z_$][A-Za-z0-9_.$]*(?:<[^>]+>)?(?:\\[\\])*)\\s*\\(").matcher(value);
        if (newExpression.find()) {
            return TypeGuess.of(newExpression.group(1));
        }

        Matcher constructor = Pattern.compile("^([A-Za-z_$][A-Za-z0-9_.$]*(?:<[^>]+>)?)\\s*\\(.*\\)$").matcher(value);
        if (constructor.matches()) {
            String typeName = constructor.group(1);
            String simpleName = simpleTypeName(typeName);
            if (!simpleName.isBlank() && Character.isUpperCase(simpleName.charAt(0))) {
                return TypeGuess.of(constructorImplementation(typeName));
            }
        }

        int ternaryQ = topLevelOperatorIndex(value, List.of("?"));
        if (ternaryQ > 0 && !Character.isJavaIdentifierPart(value.charAt(ternaryQ - 1))) {
            String rest = value.substring(ternaryQ + 1).trim();
            int colonIdx = topLevelOperatorIndex(rest, List.of(":"));
            if (colonIdx >= 0) {
                // If we have an expected type (from context), use it to help infer branches
                TypeGuess thenType = inferExpressionType(rest.substring(0, colonIdx).trim(), context, expected);
                TypeGuess elseType = inferExpressionType(rest.substring(colonIdx + 1).trim(), context, expected);
                if (thenType.isKnown() && !thenType.isNullLiteral()) {
                    return thenType;
                }
                if (elseType.isKnown() && !elseType.isNullLiteral()) {
                    return elseType;
                }
            }
        }

        // Help lambda inference if we are in an assignment or call where the expected type is known
        if (arrowIndex >= 0) {
             // If the lambda is assigned to a variable with known type, we could use it here.
             // This requires passing down the expected type to inferExpressionType.
        }

        if (startsWithBooleanNegation(value)) {
            return TypeGuess.of("boolean");
        }
        if (topLevelOperatorIndex(value, List.of("||", "&&", "==", "!=", "<=", ">=", "<", ">")) >= 0
                || INSTANCEOF_ALIAS.matcher(value).find()) {
            return TypeGuess.of("boolean");
        }
        Optional<TypeGuess> numericExpression = inferNumericExpressionType(value, context);
        if (numericExpression.isPresent()) {
            return numericExpression.get();
        }

        int callOpen = callOpenParen(value);
        if (callOpen > 0) {
            String callName = callNameBefore(value, callOpen);
            if (!callName.isBlank()) {
                List<TypedArgument> arguments = typedArgumentsForInference(value.substring(callOpen + 1, value.length() - 1), context);
                TypeGuess returnType = context.returnType(callName, arguments);
                if (returnType.isKnown()) {
                    return returnType;
                }
                // callNameBefore stops at the first non-identifier character, so a non-identifier receiver
                // (string literal, parenthesised expression, call result) is lost. Recover it directly so e.g.
                // "x".ext() or foo().ext() infers its return type.
                String receiver = receiverBeforeMethod(value, callOpen);
                if (!receiver.isBlank()) {
                    String method = callName.substring(callName.lastIndexOf('.') + 1);
                    TypeGuess receiverType = inferExpressionType(receiver, context);
                    if (receiverType.isKnown()) {
                        TypeGuess received = context.returnTypeForReceiverType(receiverType.javaType(), method, arguments);
                        if (received.isKnown()) {
                            return received;
                        }
                    }
                }
            }
        }

        TypeGuess enumConstant = enumConstantAccessType(value, context);
        if (enumConstant.isKnown()) {
            return enumConstant;
        }

        TypeGuess propertyType = propertyType(value, context);
        if (propertyType.isKnown()) {
            return propertyType;
        }

        List<String> additiveParts = splitTopLevel(value, '+');
        if (additiveParts.size() > 1) {
            boolean hasString = additiveParts.stream()
                    .map(part -> inferExpressionType(part, context))
                    .anyMatch(type -> type.javaType().equals("String") || type.javaType().equals("java.lang.String"));
            if (hasString) {
                return TypeGuess.of("String");
            }
        }

        return TypeGuess.unknown();
    }
    AstExpression expressionAst(String expression, MethodContext context) {
        return new ExpressionSemanticChecker(new TypeCheckerExpressionSupport(context)).parse(expression);
    }
    boolean astTypeCanShortCircuitInference(AstExpression ast) {
        // The ANTLR-backed AST resolves these node types reliably, including cases the regex inference
        // below mishandles. Constructors in particular carry the correct implementation type even when
        // the type arguments nest generics (e.g. Map<String, List<Integer>>()), which the regex path
        // misreads as a boolean comparison on the top-level '<' / '>'.
        return ast instanceof LambdaExpression
                || ast instanceof MethodReferenceExpression
                || (ast instanceof ConstructorExpression && ast.resolvedType().isKnown())
                // Shift (<<, >>, >>>) and numeric bitwise (&, |, ^) expressions carry a numeric result
                // type from buildShift / the bitwise builders. The regex inference below reads a bare
                // '<' / '>' as a relational comparison (boolean) and does not type bitwise at all
                // (Object), so without the AST short-circuit `let x = 1 << 4` emits invalid Java and
                // `let m = 0xFF & x` emits an imprecise Object. The known-type guard keeps this to the
                // numeric cases (boolean operands are rejected earlier and never resolve to a type here).
                || (ast instanceof BinaryExpression binary && isShiftOrBitwiseOperator(binary.operator()) && ast.resolvedType().isKnown())
                // A conditional with mixed numeric branches has the binary-numeric-promoted type
                // (buildTernary's ternaryType): `cond ? 1 : 2.0` is double, `cond ? 1 : 2L` is long.
                // The regex inference picks one branch instead, so `let x = cond ? 1 : 2.0` would emit
                // `final int x = ...` — invalid Java (lossy conversion). The known-type guard means
                // incompatible branches (unknown type) still fall through to the normal checks.
                || (ast instanceof TernaryExpression && ast.resolvedType().isKnown());
    }
    static boolean isShiftOrBitwiseOperator(String operator) {
        return operator.equals("<<") || operator.equals(">>") || operator.equals(">>>")
                || operator.equals("&") || operator.equals("|") || operator.equals("^");
    }
    Optional<TypeGuess> inferNumericExpressionType(String value, MethodContext context) {
        for (String operator : List.of("+", "-", "*", "/", "%")) {
            int operatorIndex = topLevelOperatorIndex(value, List.of(operator));
            if (operatorIndex <= 0) {
                continue;
            }
            TypeGuess left = inferExpressionType(value.substring(0, operatorIndex), context);
            TypeGuess right = inferExpressionType(value.substring(operatorIndex + operator.length()), context);
            if (operator.equals("+") && (isStringType(left) || isStringType(right))) {
                return Optional.of(TypeGuess.of("String"));
            }
            if (isNumericType(left) && isNumericType(right)) {
                return Optional.of(TypeGuess.of(promotedNumericType(left.javaType(), right.javaType())));
            }
        }
        return Optional.empty();
    }
    boolean isStringType(TypeGuess type) {
        return type.javaType().equals("String") || type.javaType().equals("java.lang.String");
    }
    boolean isNumericType(TypeGuess type) {
        // Delegates to primitiveNumericType so primitives, fully-qualified boxes
        // (java.lang.Integer) and simple-name boxes (Integer, e.g. the element type returned by
        // List<Integer>.get) are all recognized — they unbox to a numeric primitive in Java.
        return switch (primitiveNumericType(type.javaType())) {
            case "byte", "short", "int", "long", "float", "double", "char" -> true;
            default -> false;
        };
    }
    String promotedNumericType(String left, String right) {
        String normalizedLeft = primitiveNumericType(left);
        String normalizedRight = primitiveNumericType(right);
        if (normalizedLeft.equals("double") || normalizedRight.equals("double")) {
            return "double";
        }
        if (normalizedLeft.equals("float") || normalizedRight.equals("float")) {
            return "float";
        }
        if (normalizedLeft.equals("long") || normalizedRight.equals("long")) {
            return "long";
        }
        return "int";
    }
    String primitiveNumericType(String type) {
        return switch (type) {
            case "java.lang.Byte", "Byte" -> "byte";
            case "java.lang.Short", "Short" -> "short";
            case "java.lang.Integer", "Integer" -> "int";
            case "java.lang.Long", "Long" -> "long";
            case "java.lang.Float", "Float" -> "float";
            case "java.lang.Double", "Double" -> "double";
            case "java.lang.Character", "Character" -> "char";
            default -> type;
        };
    }
    TypeGuess enumConstantAccessType(String value, MethodContext context) {
        int dot = value.lastIndexOf('.');
        if (dot <= 0 || dot >= value.length() - 1 || value.indexOf('(') >= 0 || value.indexOf('[') >= 0) {
            return TypeGuess.unknown();
        }
        String member = value.substring(dot + 1).trim();
        if (member.isEmpty() || !Character.isJavaIdentifierStart(member.charAt(0))
                || !member.chars().allMatch(Character::isJavaIdentifierPart)) {
            return TypeGuess.unknown();
        }
        ClassSymbol symbol = symbols.lookupClass(value.substring(0, dot).trim(), context.unit);
        if (symbol != null && symbol.isEnum && symbol.enumConstants.contains(member)) {
            return TypeGuess.of(symbol.name());
        }
        return TypeGuess.unknown();
    }
    TypeGuess propertyType(String expression, MethodContext context) {
        int dot = expression.lastIndexOf('.');
        if (dot <= 0 || dot == expression.length() - 1 || expression.indexOf('(') >= 0) {
            return TypeGuess.unknown();
        }
        String owner = expression.substring(0, dot);
        String property = expression.substring(dot + 1);
        return propertyType(inferExpressionType(owner, context), property, context);
    }
    TypeGuess propertyType(TypeGuess ownerType, String property, MethodContext context) {
        if (!ownerType.isKnown() || ownerType.isNullLiteral()) {
            return TypeGuess.unknown();
        }
        AffogatoSymbolResolver.PropertyHop hop = symbols.resolvePropertyHopOnType(ownerType.javaType(), property, context);
        return hop == null ? TypeGuess.unknown() : hop.resultType();
    }
    boolean isGetterSetterBackedPropertyAccess(PropertyAccessExpression property, MethodContext context) {
        TypeGuess receiverType = property.receiver().resolvedType().isKnown()
                ? property.receiver().resolvedType()
                : inferExpressionType(property.receiver().source(), context);
        if (!receiverType.isKnown() || receiverType.isNullLiteral()) {
            return false;
        }
        AffogatoSymbolResolver.PropertyHop hop = symbols.resolvePropertyHopOnType(receiverType.javaType(), property.property(), context);
        return hop != null && hop.call();
    }
    boolean isCompoundAssignmentSource(String source) {
        int operatorStart = topLevelAssignmentStart(source.trim());
        if (operatorStart < 0) {
            return false;
        }
        char operator = source.charAt(operatorStart);
        return operator == '+' || operator == '-' || operator == '*' || operator == '/' || operator == '%';
    }
    Optional<TypeGuess> elementType(TypeGuess iterableType) {
        if (!iterableType.isKnown() || iterableType.isNullLiteral()) {
            return Optional.empty();
        }
        String type = iterableType.javaType();
        int genericStart = type.indexOf('<');
        int genericEnd = type.lastIndexOf('>');
        if (genericStart >= 0 && genericEnd > genericStart) {
            String firstArgument = splitTopLevel(type.substring(genericStart + 1, genericEnd), ',').get(0).trim();
            if (!firstArgument.isBlank()) {
                return Optional.of(TypeGuess.of(firstArgument));
            }
        }
        if (type.endsWith("[]")) {
            return Optional.of(TypeGuess.of(type.substring(0, type.length() - 2)));
        }
        return Optional.empty();
    }
    List<TypedArgument> typedArgumentsForInference(String args, MethodContext context) {
        List<TypedArgument> arguments = new ArrayList<>();
        if (args.isBlank()) {
            return arguments;
        }
        for (String part : splitTopLevel(args, ',')) {
            int equals = namedArgumentEquals(part);
            if (equals > 0) {
                String name = part.substring(0, equals).trim();
                String value = part.substring(equals + 1).trim();
                arguments.add(new TypedArgument(name, value, inferExpressionType(value, context), expressionAst(value, context)));
            } else if (!part.trim().isBlank()) {
                String value = part.trim();
                arguments.add(new TypedArgument("", value, inferExpressionType(value, context), expressionAst(value, context)));
            }
        }
        return arguments;
    }
    String inferArrayElementType(List<String> elements, MethodContext context) {
        if (elements.isEmpty()) {
            return "Object";
        }
        // All elements must classify to the same numeric literal type (via the shared classifier);
        // otherwise fall back to a uniform String/boolean array, or Object for anything mixed.
        String firstNumeric = numericLiteralType(elements.get(0));
        if (firstNumeric != null && elements.stream().allMatch(e -> firstNumeric.equals(numericLiteralType(e)))) {
            return firstNumeric;
        }
        boolean allString = elements.stream().allMatch(e -> e.startsWith("\""));
        if (allString) {
            return "String";
        }
        boolean allBoolean = elements.stream().allMatch(e -> e.equals("true") || e.equals("false"));
        if (allBoolean) {
            return "boolean";
        }
        // Object elements (e.g. constructor calls): if every element infers to the same known type, use
        // it so `[Person(...), Person(...)]` becomes `Person[]` rather than a too-wide `Object[]`.
        if (context != null) {
            TypeGuess first = inferExpressionType(elements.get(0), context);
            if (first.isKnown() && !first.isNullLiteral()) {
                boolean uniform = elements.stream().allMatch(e -> {
                    TypeGuess elementType = inferExpressionType(e, context);
                    return elementType.isKnown() && elementType.javaType().equals(first.javaType());
                });
                if (uniform) {
                    return first.javaType();
                }
            }
        }
        return "Object";
    }
    void validateMethodCalls(String expression, MethodContext context) {
        int index = 0;
        while (index < expression.length()) {
            int open = nextUnquotedOpenParen(expression, index);
            if (open < 0) {
                return;
            }
            int close = findMatching(expression, open, '(', ')');
            if (close < 0) {
                return;
            }
            String callName = callNameBefore(expression, open);
            List<TypedArgument> arguments = typedArgumentsForInference(expression.substring(open + 1, close), context);
            String receiver = receiverBeforeMethod(expression, open);
            if (!receiver.isBlank()) {
                    String methodName = callName.substring(callName.lastIndexOf('.') + 1);
                    TypeGuess receiverType = inferExpressionType(receiver, context);
                    if (receiverType.isKnown()) {
                        TypeGuess returnType = context.returnTypeForReceiverType(receiverType.javaType(), methodName, arguments);
                        if (!returnType.isKnown()) {
                            diagnostics.add(error(
                                    context.unit.sourceFile(),
                                context.currentLine,
                                context.currentColumn,
                                "AFFOGATO_CALL_RESOLUTION",
                                "Cannot resolve call " + methodName + " on " + receiverType.javaType() + "."
                        ));
                    }
                    index = close + 1;
                    continue;
                }
            }
            if (shouldValidateCall(callName, expression, open, context)) {
                TypeGuess returnType = context.returnType(callName, arguments);
                if (!returnType.isKnown()) {
                    diagnostics.add(error(
                            context.unit.sourceFile(),
                            context.currentLine,
                            context.currentColumn,
                            "AFFOGATO_CALL_RESOLUTION",
                            "Cannot resolve call " + callName + "."
                    ));
                }
            }
            index = close + 1;
        }
    }
    boolean shouldValidateCall(String callName, String expression, int openIndex, MethodContext context) {
        if (callName.isBlank() || callName.equals("not") || callName.equals("super") || callName.equals("this")) {
            return false;
        }
        // 'println' is the magic built-in unless the current class or scope defines it.
        if (callName.equals("println")
                && !context.hasCurrentMethod("println")
                && !context.variableTypes.containsKey("println")) {
            return false;
        }
        if (isPrecededByNew(expression, openIndex - callName.length())) {
            return false;
        }
        String simpleName = callName;
        int dot = simpleName.lastIndexOf('.');
        if (dot >= 0) {
            String owner = callName.substring(0, dot);
            if (context.variableTypes.containsKey(owner)) {
                return true;
            }
            String firstOwnerPart = owner.contains(".") ? owner.substring(0, owner.indexOf('.')) : owner;
            return !firstOwnerPart.isBlank() && Character.isUpperCase(firstOwnerPart.charAt(0));
        }
        return context.hasCurrentMethod(callName)
                || context.hasStaticImport(callName)
                || Character.isLowerCase(callName.charAt(0));
    }
    boolean isPrecededByNew(String expression, int startIndex) {
        int previous = startIndex - 1;
        while (previous >= 0 && Character.isWhitespace(expression.charAt(previous))) {
            previous--;
        }
        int end = previous + 1;
        while (previous >= 0 && Character.isJavaIdentifierPart(expression.charAt(previous))) {
            previous--;
        }
        return expression.substring(previous + 1, end).equals("new");
    }
    void validateExplicitConstructorCalls(String expression, MethodContext context) {
        int index = 0;
        while (index < expression.length()) {
            int newIndex = expression.indexOf("new", index);
            if (newIndex < 0) {
                return;
            }
            if (!isWordAt(expression, newIndex, "new")) {
                index = newIndex + 3;
                continue;
            }
            int typeStart = newIndex + 3;
            while (typeStart < expression.length() && Character.isWhitespace(expression.charAt(typeStart))) {
                typeStart++;
            }
            if (typeStart >= expression.length() || !Character.isJavaIdentifierStart(expression.charAt(typeStart))) {
                index = newIndex + 3;
                continue;
            }
            int typeEnd = readExplicitConstructorTypeEnd(expression, typeStart);
            if (typeEnd <= typeStart || typeEnd >= expression.length() || expression.charAt(typeEnd) != '(') {
                index = typeEnd <= typeStart ? newIndex + 3 : typeEnd;
                continue;
            }
            int close = findMatching(expression, typeEnd, '(', ')');
            if (close < 0) {
                return;
            }
            String typeName = expression.substring(typeStart, typeEnd).trim();
            validateConstructorCall(typeName, typeName, expression.substring(typeEnd + 1, close), context);
            index = close + 1;
        }
    }
    boolean isWordAt(String expression, int index, String word) {
        if (!expression.startsWith(word, index)) {
            return false;
        }
        int before = index - 1;
        int after = index + word.length();
        return (before < 0 || !Character.isJavaIdentifierPart(expression.charAt(before)))
                && (after >= expression.length() || !Character.isJavaIdentifierPart(expression.charAt(after)));
    }
    void validateCasts(String expression, MethodContext context) {
        Matcher matcher = AS_CAST.matcher(expression);
        while (matcher.find()) {
            TypeGuess source = inferExpressionType(matcher.group(1), context);
            String targetType = stripNullableSuffix(matcher.group(2));
            if (source.isKnown()
                    && !source.isNullLiteral()
                    && !source.isLambda()
                    && !context.javaResolver.castPossible(source, targetType, context.unit)) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_CAST_TYPE",
                        "Cannot cast " + source.javaType() + " to " + targetType + "."
                ));
            }
        }
    }
    void validateConstructorCall(
            String displayType,
            String resolutionType,
            String args,
            MethodContext context
    ) {
        if (symbols.lookupClass(displayType, context.unit) == null && !context.javaResolver.typeExists(resolutionType, context.unit)) {
            diagnostics.add(error(
                    context.unit.sourceFile(),
                    context.currentLine,
                    context.currentColumn,
                    "AFFOGATO_TYPE_RESOLUTION",
                    "Cannot resolve type " + displayType + "."
            ));
            return;
        }

        ClassSymbol affogatoTarget = symbols.lookupClass(displayType, context.unit);
        List<TypedArgument> arguments = typedArgumentsForInference(args, context);
        if (affogatoTarget != null) {
            Optional<ResolvedArguments> resolved = context.resolveArguments(displayType, arguments);
            if (resolved.isPresent()) {
                return;
            }
        } else {
            Optional<ResolvedArguments> resolved = context.javaResolver.resolveConstructorArguments(resolutionType, arguments, context.unit);
            if (resolved.isPresent()) {
                return;
            }
            if (context.javaResolver.lastResolutionAmbiguous()) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_CONSTRUCTOR_RESOLUTION",
                        "Ambiguous overload for constructor " + displayType + "."
                ));
                return;
            }
        }
        String failure = context.resolutionFailure();
        diagnostics.add(error(
                context.unit.sourceFile(),
                context.currentLine,
                context.currentColumn,
                "AFFOGATO_CONSTRUCTOR_RESOLUTION",
                failure.isBlank()
                        ? "Cannot resolve constructor " + displayType + "."
                        : failure.replace("call " + resolutionType, "constructor " + displayType)
        ));
    }
    boolean startsWithBooleanNegation(String value) {
        return value.startsWith("not(") || value.startsWith("!(") || value.startsWith("!");
    }
    boolean containsTopLevelMethodReference(String value) {
        return containsTopLevelOperator(value, "::");
    }
    int lambdaParameterArity(String header) {
        if (header == null) return TypeGuess.UNKNOWN_ARITY;
        String params = header.trim();
        if (params.startsWith("(") && params.endsWith(")")) {
            String inner = params.substring(1, params.length() - 1).trim();
            if (inner.isEmpty()) {
                return 0;
            }
            return splitTopLevel(inner, ',').size();
        }
        return params.isEmpty() ? UNKNOWN_ARITY : 1;
    }
    boolean containsTopLevelOperator(String value, String operator) {
        return topLevelOperatorIndex(value, List.of(operator)) >= 0;
    }
    int topLevelOperatorIndex(String value, List<String> operators) {
        int angle = 0;
        int paren = 0;
        int bracket = 0;
        boolean inString = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            char previous = index > 0 ? value.charAt(index - 1) : '\0';
            if (current == '"' && previous != '\\') {
                inString = !inString;
            }
            if (inString) {
                continue;
            }
            if (angle == 0 && paren == 0 && bracket == 0) {
                for (String operator : operators) {
                    if (value.startsWith(operator, index)) {
                        return index;
                    }
                }
            }
            if (current == '<') {
                angle++;
            } else if (current == '>') {
                angle = Math.max(0, angle - 1);
            } else if (current == '(') {
                paren++;
            } else if (current == ')') {
                paren = Math.max(0, paren - 1);
            } else if (current == '[') {
                bracket++;
            } else if (current == ']') {
                bracket = Math.max(0, bracket - 1);
            }
        }
        return -1;
    }
    int callOpenParen(String value) {
        if (!value.endsWith(")")) {
            return -1;
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '(' && findMatching(value, index, '(', ')') == value.length() - 1) {
                return index;
            }
        }
        return -1;
    }
    String stripOuterParens(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("(") && trimmed.endsWith(")") && findMatching(trimmed, 0, '(', ')') == trimmed.length() - 1) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }
    List<String> splitTopLevel(String text, char delimiter) {
        List<String> result = new ArrayList<>();
        int start = 0;
        int angle = 0;
        int paren = 0;
        int bracket = 0;
        int brace = 0;
        boolean inString = false;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            char previous = index > 0 ? text.charAt(index - 1) : '\0';
            if (current == '"' && previous != '\\') {
                inString = !inString;
            }
            if (inString) {
                continue;
            }
            if (current == '<') {
                angle++;
            } else if (current == '>') {
                angle = Math.max(0, angle - 1);
            } else if (current == '(') {
                paren++;
            } else if (current == ')') {
                paren = Math.max(0, paren - 1);
            } else if (current == '[') {
                bracket++;
            } else if (current == ']') {
                bracket = Math.max(0, bracket - 1);
            } else if (current == '{') {
                brace++;
            } else if (current == '}') {
                brace = Math.max(0, brace - 1);
            } else if (current == delimiter && angle == 0 && paren == 0 && bracket == 0 && brace == 0) {
                result.add(text.substring(start, index));
                start = index + 1;
            }
        }
        result.add(text.substring(start));
        return result;
    }
    int findMatching(String text, int openIndex, char openChar, char closeChar) {
        int depth = 0;
        boolean inString = false;
        for (int index = openIndex; index < text.length(); index++) {
            char current = text.charAt(index);
            char previous = index > 0 ? text.charAt(index - 1) : '\0';
            if (current == '"' && previous != '\\') {
                inString = !inString;
            }
            if (inString) {
                continue;
            }
            if (current == openChar) {
                depth++;
            } else if (current == closeChar) {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }
    String callNameBefore(String expression, int openIndex) {
        int end = openIndex - 1;
        while (end >= 0 && Character.isWhitespace(expression.charAt(end))) {
            end--;
        }
        int start = end;
        while (start >= 0) {
            char current = expression.charAt(start);
            if (Character.isJavaIdentifierPart(current) || current == '.') {
                start--;
            } else {
                break;
            }
        }
        return expression.substring(start + 1, end + 1);
    }
    String receiverBeforeMethod(String value, int callOpen) {
        int nameEnd = callOpen;
        while (nameEnd > 0 && Character.isWhitespace(value.charAt(nameEnd - 1))) {
            nameEnd--;
        }
        int nameStart = nameEnd;
        while (nameStart > 0 && Character.isJavaIdentifierPart(value.charAt(nameStart - 1))) {
            nameStart--;
        }
        int dot = nameStart - 1;
        while (dot >= 0 && Character.isWhitespace(value.charAt(dot))) {
            dot--;
        }
        if (dot >= 0 && value.charAt(dot) == '.') {
            // Extract only the immediate receiver chain ending at the dot, not the whole prefix.
            // For `"p" + a.label()` the receiver is `a`, not `"p" + a` (which infers as String and
            // makes `label()` look like a call on String). receiverStartInBuffer handles identifier
            // chains, call/array suffixes and string literals, stopping at a preceding operator.
            int start = receiverStartInBuffer(new StringBuilder(value.substring(0, dot)));
            return value.substring(start < 0 ? 0 : start, dot).trim();
        }
        return "";
    }
    int stringLiteralEnd(String expression, int openQuoteIndex) {
        int index = openQuoteIndex + 1;
        while (index < expression.length()) {
            char c = expression.charAt(index);
            if (c == '\\') {
                index += 2;
                continue;
            }
            if (c == '"') {
                return index + 1;
            }
            index++;
        }
        return expression.length();
    }
    String stripNullableSuffix(String typeName) {
        String type = stripTypeUseAnnotations(typeName.trim());
        if (type.endsWith("?") || type.endsWith("!")) {
            return type.substring(0, type.length() - 1);
        }
        return type;
    }
    String stripTypeUseAnnotations(String typeName) {
        return typeName.replaceAll("@(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Za-z_$][A-Za-z0-9_$]*\\s+", "");
    }
    String simpleTypeName(String type) {
        String cleaned = type;
        int generic = cleaned.indexOf('<');
        if (generic >= 0) {
            cleaned = cleaned.substring(0, generic);
        }
        while (cleaned.endsWith("[]")) {
            cleaned = cleaned.substring(0, cleaned.length() - 2);
        }
        int dot = cleaned.lastIndexOf('.');
        return dot >= 0 ? cleaned.substring(dot + 1) : cleaned;
    }
    String constructorImplementation(String typeName) {
        if (typeName.startsWith("Map<")) {
            return "java.util.HashMap" + typeName.substring("Map".length());
        }
        if (typeName.startsWith("List<")) {
            return "java.util.ArrayList" + typeName.substring("List".length());
        }
        if (typeName.startsWith("Set<")) {
            return "java.util.HashSet" + typeName.substring("Set".length());
        }
        return typeName;
    }
    boolean containsTopLevelArrow(String value) {
        return containsTopLevelOperator(value, "->");
    }
    int namedArgumentEquals(String part) {
        int angle = 0;
        int paren = 0;
        int brace = 0;
        boolean inString = false;
        for (int index = 0; index < part.length(); index++) {
            char current = part.charAt(index);
            char previous = index > 0 ? part.charAt(index - 1) : '\0';
            if (current == '"' && previous != '\\') {
                inString = !inString;
            }
            if (inString) {
                continue;
            }
            if (current == '<') {
                angle++;
            } else if (current == '>') {
                angle = Math.max(0, angle - 1);
            } else if (current == '(') {
                paren++;
            } else if (current == ')') {
                paren = Math.max(0, paren - 1);
            } else if (current == '{') {
                brace++;
            } else if (current == '}') {
                brace = Math.max(0, brace - 1);
            } else if (current == '=' && angle == 0 && paren == 0 && brace == 0) {
                char next = index + 1 < part.length() ? part.charAt(index + 1) : '\0';
                if (previous != '=' && previous != '!' && previous != '<' && previous != '>' && next != '=') {
                    return index;
                }
            }
        }
        return -1;
    }
    int matchBackward(StringBuilder buffer, int closeIndex, char openChar, char closeChar) {
        int depth = 0;
        boolean inString = false;
        for (int index = closeIndex; index >= 0; index--) {
            char current = buffer.charAt(index);
            if (current == '"' && (index == 0 || buffer.charAt(index - 1) != '\\')) {
                inString = !inString;
            }
            if (inString) {
                continue;
            }
            if (current == closeChar) {
                depth++;
            } else if (current == openChar) {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }
    int topLevelAssignmentStart(String expression) {
        int paren = 0;
        int bracket = 0;
        int brace = 0;
        boolean inString = false;
        for (int index = 0; index < expression.length(); index++) {
            char current = expression.charAt(index);
            char previous = index > 0 ? expression.charAt(index - 1) : '\0';
            if (current == '"' && previous != '\\') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (paren == 0 && bracket == 0 && brace == 0) {
                char next = index + 1 < expression.length() ? expression.charAt(index + 1) : '\0';
                char after = index + 2 < expression.length() ? expression.charAt(index + 2) : '\0';
                if ((current == '+' || current == '-' || current == '*' || current == '/' || current == '%')
                        && next == '=' && after != '=') {
                    return index;
                }
                if (current == '=' && next != '='
                        && previous != '=' && previous != '!' && previous != '<' && previous != '>'
                        && previous != '+' && previous != '-' && previous != '*' && previous != '/' && previous != '%') {
                    return index;
                }
            }
            switch (current) {
                case '(' -> paren++;
                case ')' -> paren = Math.max(0, paren - 1);
                case '[' -> bracket++;
                case ']' -> bracket = Math.max(0, bracket - 1);
                case '{' -> brace++;
                case '}' -> brace = Math.max(0, brace - 1);
                default -> { }
            }
        }
        return -1;
    }
    int nextUnquotedOpenParen(String expression, int from) {
        int index = from;
        int length = expression.length();
        while (index < length) {
            char c = expression.charAt(index);
            if (c == '"') {
                index = stringLiteralEnd(expression, index);
            } else if (c == '(') {
                return index;
            } else {
                index++;
            }
        }
        return -1;
    }
    int readExplicitConstructorTypeEnd(String expression, int index) {
        int cursor = index;
        boolean readPart = false;
        while (cursor < expression.length()) {
            if (!Character.isJavaIdentifierStart(expression.charAt(cursor))) {
                break;
            }
            readPart = true;
            cursor++;
            while (cursor < expression.length() && Character.isJavaIdentifierPart(expression.charAt(cursor))) {
                cursor++;
            }
            if (cursor < expression.length() && expression.charAt(cursor) == '.') {
                cursor++;
                continue;
            }
            break;
        }
        if (!readPart) {
            return index;
        }
        if (cursor < expression.length() && expression.charAt(cursor) == '<') {
            int angle = 1;
            cursor++;
            while (cursor < expression.length() && angle > 0) {
                char current = expression.charAt(cursor);
                if (current == '<') {
                    angle++;
                } else if (current == '>') {
                    angle--;
                }
                cursor++;
            }
        }
        while (cursor + 1 < expression.length() && expression.charAt(cursor) == '[' && expression.charAt(cursor + 1) == ']') {
            cursor += 2;
        }
        while (cursor < expression.length() && Character.isWhitespace(expression.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }
    int receiverStartInBuffer(StringBuilder buffer) {
        int i = buffer.length() - 1;
        while (i >= 0 && Character.isWhitespace(buffer.charAt(i))) {
            i--;
        }
        if (i < 0) {
            return -1;
        }
        char last = buffer.charAt(i);
        if (!(Character.isJavaIdentifierPart(last) || last == ')' || last == ']' || last == '"')) {
            return -1;
        }
        int end = i;
        while (i >= 0) {
            char c = buffer.charAt(i);
            if (c == ')' || c == ']') {
                int open = matchBackward(buffer, i, c == ')' ? '(' : '[', c);
                if (open < 0) {
                    return -1;
                }
                i = open - 1;
            } else if (c == '"') {
                int open = stringStartBackward(buffer, i);
                if (open < 0) {
                    return -1;
                }
                i = open - 1;
            } else if (Character.isJavaIdentifierPart(c) || c == '.') {
                i--;
            } else {
                break;
            }
        }
        int start = i + 1;
        while (start <= end && Character.isWhitespace(buffer.charAt(start))) {
            start++;
        }
        // Pull in a preceding `new` keyword so constructor receivers stay intact.
        int before = start - 1;
        while (before >= 0 && Character.isWhitespace(buffer.charAt(before))) {
            before--;
        }
        int wordStart = before;
        while (wordStart >= 0 && Character.isJavaIdentifierPart(buffer.charAt(wordStart))) {
            wordStart--;
        }
        if (buffer.substring(wordStart + 1, before + 1).equals("new")) {
            start = wordStart + 1;
        }
        return start <= end ? start : -1;
    }
    int stringStartBackward(StringBuilder buffer, int closeQuoteIndex) {
        for (int index = closeQuoteIndex - 1; index >= 0; index--) {
            if (buffer.charAt(index) == '"' && (index == 0 || buffer.charAt(index - 1) != '\\')) {
                return index;
            }
        }
        return -1;
    }
    final class TypeCheckerExpressionSupport implements ExpressionSemanticChecker.Support {
        private final MethodContext context;

        TypeCheckerExpressionSupport(MethodContext context) {
            this.context = context;
        }

        @Override
        public String stripOuterParens(String text) {
            return AffogatoTypeChecker.this.stripOuterParens(text);
        }

        @Override
        public boolean containsTopLevelMethodReference(String value) {
            return AffogatoTypeChecker.this.containsTopLevelMethodReference(value);
        }

        @Override
        public int topLevelOperatorIndex(String value, List<String> operators) {
            return AffogatoTypeChecker.this.topLevelOperatorIndex(value, operators);
        }

        @Override
        public int lambdaParameterArity(String header) {
            return AffogatoTypeChecker.this.lambdaParameterArity(header);
        }

        @Override
        public int stringLiteralEnd(String expression, int openQuoteIndex) {
            return AffogatoTypeChecker.this.stringLiteralEnd(expression, openQuoteIndex);
        }

        @Override
        public String stripNullableSuffix(String typeName) {
            return AffogatoTypeChecker.this.stripNullableSuffix(typeName);
        }

        @Override
        public int namedArgumentEquals(String expression) {
            return AffogatoTypeChecker.this.namedArgumentEquals(expression);
        }

        @Override
        public int callOpenParen(String value) {
            return AffogatoTypeChecker.this.callOpenParen(value);
        }

        @Override
        public String callNameBefore(String expression, int openIndex) {
            return AffogatoTypeChecker.this.callNameBefore(expression, openIndex);
        }

        @Override
        public String simpleTypeName(String type) {
            return AffogatoTypeChecker.this.simpleTypeName(type);
        }

        @Override
        public String constructorImplementation(String typeName) {
            return AffogatoTypeChecker.this.constructorImplementation(typeName);
        }

        @Override
        public List<String> splitTopLevel(String text, char delimiter) {
            return AffogatoTypeChecker.this.splitTopLevel(text, delimiter);
        }

        @Override
        public boolean startsWithBooleanNegation(String value) {
            return AffogatoTypeChecker.this.startsWithBooleanNegation(value);
        }

        @Override
        public boolean isStringType(TypeGuess type) {
            return AffogatoTypeChecker.this.isStringType(type);
        }

        @Override
        public boolean isNumericType(TypeGuess type) {
            return AffogatoTypeChecker.this.isNumericType(type);
        }

        @Override
        public String promotedNumericType(String left, String right) {
            return AffogatoTypeChecker.this.promotedNumericType(left, right);
        }

        @Override
        public String variableType(String name) {
            return context.identifierType(name).orElse(null);
        }
    }
}
