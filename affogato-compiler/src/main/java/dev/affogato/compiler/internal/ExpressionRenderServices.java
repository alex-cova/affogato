package dev.affogato.compiler.internal;

import dev.affogato.compiler.parser.AffogatoParser;
import dev.affogato.compiler.internal.TranspilerTypes.*;
import org.antlr.v4.runtime.ParserRuleContext;

import java.util.List;

/**
 * Validation-time services used by {@link ExpressionRenderer} during type checking and codegen.
 */
interface ExpressionRenderServices {
    String getExpectedArrayElementType();

    void setExpectedArrayElementType(String val);

    TypeGuess inferExpressionType(String expression, MethodContext context);

    TypeGuess inferExpressionType(String expression, MethodContext context, TypeGuess expected);

    String inferArrayElementType(List<String> elements, MethodContext context);

    String transformStringInterpolation(String expression, MethodContext context);

    String simpleTypeName(String type);

    ClassSymbol classSymbol(String type, CompilationUnit unit);

    AffogatoSymbolResolver.PropertyHop resolvePropertyHopOnType(String ownerType, String property, MethodContext context);

    String getterName(String fieldName, TypeRef type);

    String setterName(String fieldName);

    boolean isGetterSetterBackedPropertyAccess(PropertyAccessExpression property, MethodContext context);

    FieldSymbol fieldForOwnerType(String ownerType, String property, MethodContext context);

    String constructorImplementation(String typeName);

    String lastParameterType(String callName, MethodContext context);

    String supplierListElementType(String typeName);

    String stripNullableSuffix(String typeName);

    void writeBlockStatements(StringBuilder out, CompilationUnit unit, AffogatoParser.BlockContext block, MethodContext context, int indent);

    void writeStatement(StringBuilder out, CompilationUnit unit, AffogatoParser.StatementContext statement, MethodContext context, int indent);

    AstExpression expressionAst(String expression, MethodContext context);

    String mergeTrailingClosure(String exprText, String source, AffogatoParser.TrailingClosureContext closure, MethodContext context);

    String sourceText(String source, ParserRuleContext context);

    TypedExpression buildSwitchExpressionNode(String source, MethodContext context);
}
