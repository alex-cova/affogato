package dev.affogato.compiler.internal;

import static dev.affogato.compiler.internal.TranspilerTypes.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class MethodContext {
    final CompilationUnit unit;
    final ParsedClass currentClass;
    final String executableName;
    final TypeRef returnType;
    private final ClassSymbolTable classSymbols;
    private final Map<String, List<ExtensionSymbol>> extensionSymbols;
    final JavaResolver javaResolver;
    final Set<String> activeTypeParams;
    final Map<String, String> variableTypes = new LinkedHashMap<>();
    final Map<String, Boolean> mutableVariables = new LinkedHashMap<>();
    final Map<String, Nullability> variableNullabilities = new LinkedHashMap<>();
    /** Non-null only when generating an extension function body; the receiver type bound to {@code $this}. */
    String receiverType;
    private String resolutionFailure = "";
    int currentLine = 1;
    int currentColumn = 1;
    /** 1-based line of the expression currently being checked; 0 means use {@link #currentLine}. */
    int expressionLine;
    /** 1-based column of the expression start; 0 means use {@link #currentColumn}. */
    int expressionColumn;
    private final Deque<Set<String>> blockLocalNames = new ArrayDeque<>();
    private Set<String> localsDeclaredLaterInBlock = Set.of();

    private MethodContext(
            CompilationUnit unit,
            ParsedClass currentClass,
            String executableName,
            TypeRef returnType,
            ClassSymbolTable classSymbols,
            Map<String, List<ExtensionSymbol>> extensionSymbols,
            JavaResolver javaResolver,
            Set<String> activeTypeParams
    ) {
        this.unit = unit;
        this.currentClass = currentClass;
        this.executableName = executableName;
        this.returnType = returnType;
        this.classSymbols = classSymbols;
        this.extensionSymbols = extensionSymbols;
        this.javaResolver = javaResolver;
        this.activeTypeParams = activeTypeParams;
    }

    static MethodContext forExecutable(
            CompilationUnit unit,
            ParsedClass currentClass,
            String executableName,
            TypeRef returnType,
            ClassSymbolTable classSymbols,
            Map<String, List<ExtensionSymbol>> extensionSymbols,
            JavaResolver javaResolver,
            Set<String> activeTypeParams
    ) {
        return new MethodContext(unit, currentClass, executableName, returnType, classSymbols, extensionSymbols, javaResolver, activeTypeParams);
    }

    static MethodContext empty(
            CompilationUnit unit,
            ParsedClass currentClass,
            ClassSymbolTable classSymbols,
            Map<String, List<ExtensionSymbol>> extensionSymbols,
            JavaResolver javaResolver,
            Set<String> activeTypeParams
    ) {
        return new MethodContext(unit, currentClass, "", TypeRef.unspecified("void"), classSymbols, extensionSymbols, javaResolver, activeTypeParams);
    }

    void declareVariable(String name, TypeRef type, boolean mutable) {
        variableTypes.put(name, type.javaType());
        mutableVariables.put(name, mutable);
        variableNullabilities.put(name, type.nullability());
    }

    void pushBlockScope() {
        blockLocalNames.push(new HashSet<>());
    }

    void popBlockScope() {
        if (!blockLocalNames.isEmpty()) {
            blockLocalNames.pop();
        }
    }

    /** Returns {@code false} when {@code name} is already declared in the current block. */
    boolean declareBlockLocal(String name) {
        if (blockLocalNames.isEmpty()) {
            pushBlockScope();
        }
        return blockLocalNames.peek().add(name);
    }

    ScopeSnapshot snapshotScope() {
        return new ScopeSnapshot(
                new LinkedHashMap<>(variableTypes),
                new LinkedHashMap<>(mutableVariables),
                new LinkedHashMap<>(variableNullabilities)
        );
    }

    void restoreScope(ScopeSnapshot snapshot) {
        variableTypes.clear();
        variableTypes.putAll(snapshot.variableTypes());
        mutableVariables.clear();
        mutableVariables.putAll(snapshot.mutableVariables());
        variableNullabilities.clear();
        variableNullabilities.putAll(snapshot.variableNullabilities());
    }

    void setLocalsDeclaredLaterInBlock(Set<String> names) {
        localsDeclaredLaterInBlock = names == null ? Set.of() : Set.copyOf(names);
    }

    boolean isLocalDeclaredLaterInBlock(String name) {
        return localsDeclaredLaterInBlock.contains(name);
    }

    record ScopeSnapshot(
            Map<String, String> variableTypes,
            Map<String, Boolean> mutableVariables,
            Map<String, Nullability> variableNullabilities
    ) {
    }

    Optional<ResolvedArguments> resolveArguments(String callName, List<TypedArgument> arguments) {
        resolutionFailure = "";
        String simpleName = callName;
        int dot = simpleName.lastIndexOf('.');
        if (dot >= 0) {
            simpleName = simpleName.substring(dot + 1);
        }
        int generic = simpleName.indexOf('<');
        if (generic >= 0) {
            simpleName = simpleName.substring(0, generic);
        }

        ClassSymbol constructorTarget = classSymbols.lookup(simpleName, unit);
        if (constructorTarget != null) {
            Optional<ScoredAffogatoArguments> constructor = resolveAffogatoCandidates(
                    callName,
                    constructorTarget.constructors.stream().map(ConstructorSymbol::parameters).toList(),
                    arguments
            );
            if (constructor.isPresent()) {
                return Optional.of(constructor.get().resolved());
            }
        }

        List<MethodSymbol> currentMethods = affogatoMethods(currentClass.name(), simpleName, unit);
        if (!currentMethods.isEmpty()) {
            Optional<ScoredAffogatoArguments> method = resolveAffogatoCandidates(
                    callName,
                    currentMethods.stream().map(MethodSymbol::parameters).toList(),
                    arguments
            );
            if (method.isPresent()) {
                return Optional.of(method.get().resolved());
            }
        }

        Optional<ResolvedArguments> staticImport = javaResolver.resolveStaticMethodArguments(simpleName, arguments, unit);
        if (staticImport.isPresent()) {
            return staticImport;
        }
        if (javaResolver.lastResolutionAmbiguous()) {
            resolutionFailure = "Ambiguous overload for call " + callName + ".";
            return Optional.empty();
        }

        if (callName.contains(".")) {
            String owner = callName.substring(0, callName.lastIndexOf('.'));
            String method = callName.substring(callName.lastIndexOf('.') + 1);
            String ownerType = resolveOwnerType(owner);
            List<MethodSymbol> ownerMethods = affogatoMethods(ownerType, method, unit);
            if (!ownerMethods.isEmpty()) {
                Optional<ScoredAffogatoArguments> affogatoMethod = resolveAffogatoCandidates(
                        callName,
                        ownerMethods.stream().map(MethodSymbol::parameters).toList(),
                        arguments
                );
                if (affogatoMethod.isPresent()) {
                    return Optional.of(affogatoMethod.get().resolved());
                }
            }
            Optional<ResolvedArguments> javaMethod = javaResolver.resolveMethodArguments(ownerType, method, arguments, unit);
            if (javaMethod.isEmpty() && javaResolver.lastResolutionAmbiguous()) {
                resolutionFailure = "Ambiguous overload for call " + callName + ".";
            }
            return javaMethod;
        }

        Optional<ResolvedArguments> javaConstructor = javaResolver.resolveConstructorArguments(callName, arguments, unit);
        if (javaConstructor.isEmpty() && javaResolver.lastResolutionAmbiguous()) {
            resolutionFailure = "Ambiguous overload for call " + callName + ".";
        }
        return javaConstructor;
    }

    String resolutionFailure() {
        return resolutionFailure;
    }

    boolean hasCurrentMethod(String methodName) {
        return !affogatoMethods(currentClass.name(), methodName, unit).isEmpty();
    }

    boolean hasStaticImport(String methodName) {
        return unit.imports().stream().anyMatch(importName -> {
            if (!importName.startsWith("static ")) {
                return false;
            }
            String cleaned = importName.substring("static ".length()).trim();
            return cleaned.endsWith(".*") || cleaned.endsWith("." + methodName);
        });
    }

    TypeGuess returnType(String callName, List<TypedArgument> arguments) {
        String simpleName = callName;
        int dot = simpleName.lastIndexOf('.');
        if (dot >= 0) {
            simpleName = simpleName.substring(dot + 1);
        }

        List<MethodSymbol> currentMethods = affogatoMethods(currentClass.name(), simpleName, unit);
        if (!callName.contains(".") && !currentMethods.isEmpty()) {
            return currentMethods.stream()
                    .map(candidate -> new ScoredReturn(
                            scoreAffogatoParameters(candidate.parameters(), arguments, InvocationPhase.LOOSE).map(ScoredAffogatoArguments::score).orElse(NO_CONVERSION),
                            TypeGuess.of(candidate.returnType().javaType())
                    ))
                    .filter(candidate -> candidate.score() < NO_CONVERSION)
                    .min(Comparator.comparingInt(ScoredReturn::score))
                    .map(ScoredReturn::returnType)
                    .orElse(TypeGuess.unknown());
        }

        if (!callName.contains(".")) {
            TypeGuess staticImportReturnType = javaResolver.staticMethodReturnType(simpleName, arguments, unit)
                    .orElse(TypeGuess.unknown());
            if (staticImportReturnType.isKnown()) {
                return staticImportReturnType;
            }
        }

        if (callName.contains(".")) {
            String owner = callName.substring(0, callName.lastIndexOf('.'));
            String method = callName.substring(callName.lastIndexOf('.') + 1);
            return returnTypeForReceiverType(resolveOwnerType(owner), method, arguments);
        }

        return TypeGuess.unknown();
    }

    /** Return type of {@code method(args)} invoked on a receiver of static type {@code ownerType}. */
    TypeGuess returnTypeForReceiverType(String ownerType, String method, List<TypedArgument> arguments) {
        String resolvedOwnerType = activeTypeParams.contains(ownerType) ? "java.lang.Object" : ownerType;
        List<MethodSymbol> ownerMethods = affogatoMethods(resolvedOwnerType, method, unit);
        if (!ownerMethods.isEmpty()) {
            Optional<TypeGuess> affogatoReturn = ownerMethods.stream()
                    .map(candidate -> new ScoredReturn(
                            scoreAffogatoParameters(candidate.parameters(), arguments, InvocationPhase.LOOSE).map(ScoredAffogatoArguments::score).orElse(NO_CONVERSION),
                            TypeGuess.of(candidate.returnType().javaType())
                    ))
                    .filter(candidate -> candidate.score() < NO_CONVERSION)
                    .min(Comparator.comparingInt(ScoredReturn::score))
                    .map(ScoredReturn::returnType);
            if (affogatoReturn.isPresent()) {
                return affogatoReturn.get();
            }
        }
        TypeGuess javaReturn = javaResolver.methodReturnType(resolvedOwnerType, method, arguments, unit)
                .orElse(TypeGuess.unknown());
        if (javaReturn.isKnown()) {
            return javaReturn;
        }
        // Extension functions are the last fallback, after instance methods fail to resolve.
        return resolveExtensionCall(resolvedOwnerType, method, arguments)
                .map(match -> TypeGuess.of(match.symbol().returnType().javaType()))
                .orElse(TypeGuess.unknown());
    }

    String resolveOwnerType(String owner) {
        if ("this".equals(owner)) {
            return currentClass.name();
        }
        if ("super".equals(owner) && !currentClass.superTypes().isEmpty()) {
            for (String superType : currentClass.superTypes()) {
                ClassSymbol symbol = classSymbols.lookup(superType, unit);
                if (symbol == null || !symbol.isInterface()) {
                    return superType;
                }
            }
            return currentClass.superTypes().get(0);
        }
        String type = variableTypes.getOrDefault(owner, owner);
        return activeTypeParams.contains(type) ? "java.lang.Object" : type;
    }

    /**
     * Resolves an extension function call {@code receiver.method(args)} for a receiver of static type
     * {@code ownerType}. Extension functions are dispatched statically and only consulted after instance
     * methods fail. Candidates match the exact receiver type plus the Affogato supertype chain.
     */
    Optional<ExtensionMatch> resolveExtensionCall(String ownerType, String methodName, List<TypedArgument> arguments) {
        String resolvedOwnerType = activeTypeParams.contains(ownerType) ? "java.lang.Object" : ownerType;
        List<ExtensionSymbol> candidates = extensionCandidates(resolvedOwnerType, methodName);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        for (InvocationPhase phase : List.of(InvocationPhase.STRICT, InvocationPhase.LOOSE)) {
            ExtensionMatch best = null;
            int bestScore = NO_CONVERSION;
            for (ExtensionSymbol candidate : candidates) {
                Optional<ScoredAffogatoArguments> scored = scoreAffogatoParameters(candidate.parameters(), arguments, phase);
                if (scored.isPresent() && scored.get().score() < bestScore) {
                    bestScore = scored.get().score();
                    best = new ExtensionMatch(candidate, scored.get().resolved());
                }
            }
            if (best != null) {
                return Optional.of(best);
            }
        }
        return Optional.empty();
    }

    /**
     * Decides whether {@code receiver.method(args)} dispatches to an extension function. Mirrors the
     * member-wins precedence of {@link #resolveArguments}: returns a match only when no instance method
     * (Affogato or Java) resolves for {@code ownerType} and an extension does.
     */
    Optional<ExtensionMatch> dispatchExtension(String ownerType, String methodName, List<TypedArgument> arguments) {
        if (instanceMethodResolves(ownerType, methodName, arguments)) {
            return Optional.empty();
        }
        return resolveExtensionCall(ownerType, methodName, arguments);
    }

    private boolean instanceMethodResolves(String ownerType, String methodName, List<TypedArgument> arguments) {
        String resolvedOwnerType = activeTypeParams.contains(ownerType) ? "java.lang.Object" : ownerType;
        for (MethodSymbol candidate : affogatoMethods(resolvedOwnerType, methodName, unit)) {
            for (InvocationPhase phase : List.of(InvocationPhase.STRICT, InvocationPhase.LOOSE)) {
                if (scoreAffogatoParameters(candidate.parameters(), arguments, phase).isPresent()) {
                    return true;
                }
            }
        }
        return javaResolver.resolveMethodArguments(resolvedOwnerType, methodName, arguments, unit).isPresent();
    }

    /** True when the extension receiver type exposes a (possibly inherited) field or getter named {@code name}. */
    boolean receiverHasField(String name) {
        if (receiverType == null) {
            return false;
        }
        String resolvedReceiverType = activeTypeParams.contains(receiverType) ? "java.lang.Object" : receiverType;
        if (affogatoFieldExists(resolvedReceiverType, name)) {
            return true;
        }
        return javaResolver.getterExists(resolvedReceiverType, name, unit) || javaResolver.fieldExists(resolvedReceiverType, name, unit);
    }

    /** True when the extension receiver type exposes a (possibly inherited) method named {@code name}. */
    boolean receiverHasMethod(String name) {
        if (receiverType == null) {
            return false;
        }
        String resolvedReceiverType = activeTypeParams.contains(receiverType) ? "java.lang.Object" : receiverType;
        if (!affogatoMethods(resolvedReceiverType, name, unit).isEmpty()) {
            return true;
        }
        return javaResolver.hasMethodNamed(resolvedReceiverType, name, unit);
    }

    boolean identifierResolvesAsMember(String name) {
        return identifierType(name).isPresent();
    }

    Optional<String> identifierType(String name) {
        String localType = variableTypes.get(name);
        if (localType != null) {
            return Optional.of(localType);
        }
        if (currentClass != null) {
            Optional<String> fieldType = currentClass.fields().stream()
                    .filter(field -> field.name().equals(name))
                    .map(field -> field.type().javaType())
                    .findFirst();
            if (fieldType.isPresent()) {
                return fieldType;
            }
            Optional<String> componentType = currentClass.compactParameters().stream()
                    .filter(parameter -> parameter.name().equals(name))
                    .map(parameter -> parameter.type().javaType())
                    .findFirst();
            if (componentType.isPresent()) {
                return componentType;
            }
        }
        if (receiverType != null) {
            String resolvedReceiverType = activeTypeParams.contains(receiverType) ? "java.lang.Object" : receiverType;
            Optional<String> receiverFieldType = affogatoFieldType(resolvedReceiverType, name)
                    .map(TypeRef::javaType)
                    .or(() -> javaResolver.getterReturnType(resolvedReceiverType, name, unit).map(TypeGuess::javaType))
                    .or(() -> javaResolver.fieldType(resolvedReceiverType, name, unit).map(TypeGuess::javaType));
            if (receiverFieldType.isPresent()) {
                return receiverFieldType;
            }
        }
        return Optional.empty();
    }

    private boolean affogatoFieldExists(String type, String name) {
        return affogatoFieldType(type, name).isPresent();
    }

    private Optional<TypeRef> affogatoFieldType(String type, String name) {
        Set<String> seen = new LinkedHashSet<>();
        String current = type;
        while (current != null && !current.isBlank()) {
            ClassSymbol symbol = affogatoClassSymbol(current, unit);
            if (symbol == null || !seen.add(symbol.name())) {
                break;
            }
            FieldSymbol field = symbol.fields.get(name);
            if (field != null) {
                return Optional.of(field.type());
            }
            current = symbol.extendsType();
        }
        return Optional.empty();
    }

    private List<ExtensionSymbol> extensionCandidates(String ownerType, String methodName) {
        List<ExtensionSymbol> result = new ArrayList<>();
        collectExtensionCandidates(ownerType, methodName, result, new LinkedHashSet<>());
        return result;
    }

    private void collectExtensionCandidates(String type, String methodName, List<ExtensionSymbol> out, Set<String> seen) {
        if (type == null || type.isBlank()) {
            return;
        }
        String simple = simpleTypeName(type);
        if (!seen.add(simple)) {
            return;
        }
        for (ExtensionSymbol candidate : extensionSymbols.getOrDefault(simple, List.of())) {
            if (candidate.name().equals(methodName)) {
                out.add(candidate);
            }
        }
        ClassSymbol symbol = affogatoClassSymbol(type, unit);
        if (symbol != null && !symbol.extendsType().isBlank()) {
            collectExtensionCandidates(symbol.extendsType(), methodName, out, seen);
        } else if (symbol == null) {
            // Not an Affogato type: walk the Java superclass/interface chain so an extension declared on a
            // supertype (e.g. CharSequence) is visible on a subtype receiver (e.g. String).
            for (String ancestor : javaResolver.ancestorSimpleNames(type, unit)) {
                collectExtensionCandidates(ancestor, methodName, out, seen);
            }
        }
    }

    private List<MethodSymbol> affogatoMethods(String ownerType, String methodName, CompilationUnit unit) {
        return affogatoMethods(ownerType, methodName, unit, new LinkedHashSet<>());
    }

    private List<MethodSymbol> affogatoMethods(String ownerType, String methodName, CompilationUnit unit, Set<String> seen) {
        ClassSymbol symbol = affogatoClassSymbol(ownerType, unit);
        if (symbol == null || !seen.add(symbol.name())) {
            return List.of();
        }
        List<MethodSymbol> methods = new ArrayList<>();
        methods.addAll(symbol.methods.getOrDefault(methodName, List.of()));
        if (!symbol.extendsType().isBlank()) {
            methods.addAll(affogatoMethods(symbol.extendsType(), methodName, unit, seen));
        }
        return methods;
    }

    private ClassSymbol affogatoClassSymbol(String type, CompilationUnit unit) {
        return classSymbols.lookup(type, unit);
    }

    private String simpleTypeName(String type) {
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

    private Optional<ScoredAffogatoArguments> resolveAffogatoCandidates(String callName, List<List<ParamDecl>> candidates, List<TypedArgument> arguments) {
        for (InvocationPhase phase : List.of(InvocationPhase.STRICT, InvocationPhase.LOOSE)) {
            List<ScoredAffogatoArguments> matches = candidates.stream()
                    .map(candidate -> scoreAffogatoParameters(candidate, arguments, phase))
                    .flatMap(Optional::stream)
                    .toList();
            if (!matches.isEmpty()) {
                Optional<ScoredAffogatoArguments> selected = chooseMostSpecificAffogato(matches);
                if (selected.isEmpty()) {
                    resolutionFailure = "Ambiguous overload for call " + callName + ".";
                }
                return selected;
            }
        }
        return Optional.empty();
    }

    private Optional<ScoredAffogatoArguments> chooseMostSpecificAffogato(List<ScoredAffogatoArguments> matches) {
        int bestScore = matches.stream().mapToInt(ScoredAffogatoArguments::score).min().orElse(NO_CONVERSION);
        List<ScoredAffogatoArguments> best = matches.stream()
                .filter(match -> match.score() == bestScore)
                .toList();
        if (best.size() == 1) {
            return Optional.of(best.get(0));
        }
        for (ScoredAffogatoArguments candidate : best) {
            boolean moreSpecificThanAll = best.stream()
                    .filter(other -> other != candidate)
                    .allMatch(other -> javaResolver.affogatoParametersMoreSpecific(candidate.parameters(), other.parameters(), unit));
            if (moreSpecificThanAll) {
                return Optional.of(candidate);
            }
        }
        // Maximally-specific rule: override-equivalent candidates (same parameter types reached through
        // an override of a superclass method) are not ambiguous. affogatoMethods collects the most-derived
        // class first, so the first match is the effective override.
        ScoredAffogatoArguments first = best.get(0);
        boolean overrideEquivalent = best.stream()
                .allMatch(candidate -> sameParameterTypes(candidate.parameters(), first.parameters()));
        return overrideEquivalent ? Optional.of(first) : Optional.empty();
    }

    private boolean sameParameterTypes(List<ParamDecl> left, List<ParamDecl> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (!javaResolver.rawClassName(left.get(index).type().javaType()).equals(javaResolver.rawClassName(right.get(index).type().javaType()))) {
                return false;
            }
        }
        return true;
    }

    private Optional<ScoredAffogatoArguments> scoreAffogatoParameters(
            List<ParamDecl> parameters,
            List<TypedArgument> arguments,
            InvocationPhase phase
    ) {
        if (arguments.size() != parameters.size()) {
            return Optional.empty();
        }
        List<TypedArgument> ordered = new ArrayList<>();
        for (int index = 0; index < parameters.size(); index++) {
            ordered.add(null);
        }

        // Bind named arguments to their slots first, then fill the remaining slots with
        // positional arguments left-to-right. This lets a positional argument follow a named
        // one (e.g. a Swift-style trailing closure after a named argument: f(value = x) { ... }).
        for (TypedArgument argument : arguments) {
            if (argument.name().isBlank()) {
                continue;
            }
            int parameterIndex = -1;
            for (int index = 0; index < parameters.size(); index++) {
                if (parameters.get(index).name().equals(argument.name())) {
                    parameterIndex = index;
                    break;
                }
            }
            if (parameterIndex < 0 || ordered.get(parameterIndex) != null) {
                return Optional.empty();
            }
            ordered.set(parameterIndex, argument);
        }
        int slot = 0;
        for (TypedArgument argument : arguments) {
            if (!argument.name().isBlank()) {
                continue;
            }
            while (slot < parameters.size() && ordered.get(slot) != null) {
                slot++;
            }
            if (slot >= parameters.size()) {
                return Optional.empty();
            }
            ordered.set(slot++, argument);
        }

        List<String> expressions = new ArrayList<>();
        int score = 0;
        for (int index = 0; index < parameters.size(); index++) {
            TypedArgument argument = ordered.get(index);
            if (argument == null) {
                return Optional.empty();
            }
            int conversion = javaResolver.conversionScore(argument, parameters.get(index).type().javaType(), unit, phase);
            if (conversion >= NO_CONVERSION) {
                return Optional.empty();
            }
            score += conversion;
            expressions.add(argument.expression());
        }
        return Optional.of(new ScoredAffogatoArguments(score, new ResolvedArguments(expressions), parameters, phase));
    }
}
