package dev.affogato.compiler;

import java.util.Map;
import java.util.Optional;

/**
 * Stable diagnostic codes and default hints shown by {@link AffogatoDiagnosticRenderer}.
 */
public final class AffogatoDiagnosticCodes {
    private static final Map<String, String> HINTS = Map.ofEntries(
            Map.entry("AFFOGATO_ARRAY_ACCESS_TYPE", "Use an array or list receiver for [] access."),
            Map.entry("AFFOGATO_ARRAY_INDEX_TYPE", "Array indexes must be int-compatible."),
            Map.entry("AFFOGATO_ASSIGNMENT_TYPE", "Adjust the expression type or change the declared type."),
            Map.entry("AFFOGATO_CALL_RESOLUTION", "Check the method name, receiver type, imports, and argument types."),
            Map.entry("AFFOGATO_CAST_TYPE", "Use a cast target that is assignable from the source expression."),
            Map.entry("AFFOGATO_CATCH_TYPE", "Catch types must be assignable from the thrown exception type."),
            Map.entry("AFFOGATO_CLASS_LITERAL_TYPE", "Class literals cannot use erased type parameters."),
            Map.entry("AFFOGATO_COMPACT_PARAM", "Compact constructor parameters must be unique and valid."),
            Map.entry("AFFOGATO_CONDITION_TYPE", "Use a boolean expression for conditions and logical operators."),
            Map.entry("AFFOGATO_CONSTRUCTOR_RESOLUTION", "Check constructor parameter types and named argument names."),
            Map.entry("AFFOGATO_DUPLICATE_CLASS", "Rename or remove the duplicate type in this compilation unit."),
            Map.entry("AFFOGATO_DUPLICATE_LOCAL", "Use a different name or remove the earlier declaration in this block."),
            Map.entry("AFFOGATO_EXTENSION_PARAM_CONFLICT", "Rename the extension parameter so it does not shadow a member."),
            Map.entry("AFFOGATO_FIELD_TYPE", "Match the field type or change the initializer expression."),
            Map.entry("AFFOGATO_FOR_ITERABLE_TYPE", "for-in requires an array or java.lang.Iterable expression."),
            Map.entry("AFFOGATO_GUARD_FLOW", "The guard else block must return or throw on every path."),
            Map.entry("AFFOGATO_IDENTIFIER_RESOLUTION", "Declare the name before use, import it, or fix the spelling."),
            Map.entry("AFFOGATO_IMPORT_CONFLICT", "Remove or alias one of the conflicting imports."),
            Map.entry("AFFOGATO_INSTANCEOF_TYPE", "instanceof requires a reference-typed source expression."),
            Map.entry("AFFOGATO_IO", "Check file permissions and that the path exists."),
            Map.entry("AFFOGATO_JAVA_RELEASE", "Affogato currently targets Java 21 only."),
            Map.entry("AFFOGATO_LET_ASSIGN", "Use var for mutable locals, or assign to a different binding."),
            Map.entry("AFFOGATO_LOCAL_TYPE", "Add an explicit type when initializing with null or ambiguous expressions."),
            Map.entry("AFFOGATO_MAIN_SIGNATURE", "Declare the entry point as main(args: String[]) to run it with java."),
            Map.entry("AFFOGATO_NAMED_ARGS", "Check argument names, arity, and overload applicability."),
            Map.entry("AFFOGATO_NUMERIC_LITERAL", "Remove leading zeros and keep the value within int (or long with an L suffix)."),
            Map.entry("AFFOGATO_OPERATOR_TYPE", "Use operands with compatible types for this operator."),
            Map.entry("AFFOGATO_PARSE", "Fix the syntax near the highlighted token."),
            Map.entry("AFFOGATO_POLY_TARGET_TYPE", "Add an explicit type for lambdas or method references."),
            Map.entry("AFFOGATO_PROPERTY_MUTATION_EXPR", "Move the property mutation into its own statement."),
            Map.entry("AFFOGATO_PROPERTY_RESOLUTION", "Check the property name and receiver type."),
            Map.entry("AFFOGATO_RECORD_MEMBER", "Records only support declared components and generated members."),
            Map.entry("AFFOGATO_RESERVED_IDENTIFIER", "Rename the declaration; it collides with a Java reserved word and would emit invalid Java."),
            Map.entry("AFFOGATO_RETURN_FLOW", "Ensure every path returns a value or throws."),
            Map.entry("AFFOGATO_RETURN_TYPE", "Return an expression assignable to the method return type."),
            Map.entry("AFFOGATO_SOURCE_SCAN", "Check that source directories exist and are readable."),
            Map.entry("AFFOGATO_SWITCH_EXPR_BODY", "Switch expression arms must be compatible expression or block forms."),
            Map.entry("AFFOGATO_SWITCH_LABEL_TYPE", "Switch case labels must match the selector type."),
            Map.entry("AFFOGATO_SWITCH_SELECTOR_TYPE", "Switch selectors must be a supported type."),
            Map.entry("AFFOGATO_TERNARY_TYPE", "Both ternary branches must have compatible types."),
            Map.entry("AFFOGATO_THROW_TYPE", "Throw an expression assignable to java.lang.Throwable."),
            Map.entry("AFFOGATO_TYPE_RESOLUTION", "Import the type, fix the spelling, or add it to the classpath."),
            Map.entry("AFFOGATO_UNREACHABLE", "Remove dead code or reorder statements."),
            Map.entry("AFFOGATO_USE_BEFORE_INIT", "Declare the variable before use, or reorder the statements in the block."),
            Map.entry("AFFOGATO_UNSUPPORTED_ELVIS", "Use an explicit null check or a ternary expression instead of ?:."),
            Map.entry("AFFOGATO_UNSUPPORTED_NOT_NULL_ASSERTION", "Use an explicit cast or null check instead of !!."),
            Map.entry("AFFOGATO_UNSUPPORTED_SAFE_CALL", "Use an explicit null check instead of ?."),
            Map.entry("AFFOGATO_WRITE", "Check output directory permissions and disk space.")
    );

    private AffogatoDiagnosticCodes() {
    }

    public static Optional<String> hint(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(HINTS.get(code));
    }
}
