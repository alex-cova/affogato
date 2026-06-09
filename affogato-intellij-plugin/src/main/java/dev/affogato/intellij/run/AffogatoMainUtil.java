package dev.affogato.intellij.run;

import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import dev.affogato.intellij.psi.AffogatoTypes;
import dev.affogato.intellij.psi.ClassDecl;
import dev.affogato.intellij.psi.Identifier;
import dev.affogato.intellij.psi.MemberModifier;
import dev.affogato.intellij.psi.MethodDecl;
import dev.affogato.intellij.psi.MethodSignature;
import dev.affogato.intellij.psi.PackageDecl;
import dev.affogato.intellij.psi.Parameter;
import dev.affogato.intellij.psi.ParameterList;
import dev.affogato.intellij.psi.QualifiedName;
import dev.affogato.intellij.psi.TypeRef;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Shared logic for detecting the Affogato entry point, mirroring the compiler's
 * {@code validateMainSignature}: a {@code static} method named {@code main} taking a
 * single {@code String[]} parameter.
 */
public final class AffogatoMainUtil {
    private AffogatoMainUtil() {
    }

    /** Returns the enclosing {@link MethodDecl} if {@code element} is the {@code main} name leaf of a valid entry point. */
    public static @Nullable MethodDecl mainFromNameLeaf(@Nullable PsiElement element) {
        if (element == null || element.getNode() == null) {
            return null;
        }
        // Only react to the leaf ID token so the gutter icon is attached exactly once.
        if (element.getNode().getElementType() != AffogatoTypes.ID || !"main".equals(element.getText())) {
            return null;
        }
        if (!(element.getParent() instanceof Identifier identifier)) {
            return null;
        }
        if (!(identifier.getParent() instanceof MethodSignature signature) || signature.getIdentifier() != identifier) {
            return null;
        }
        MethodDecl method = PsiTreeUtil.getParentOfType(signature, MethodDecl.class);
        return isMainEntryPoint(method) ? method : null;
    }

    /** Returns the enclosing valid {@code main} {@link MethodDecl} for any element inside it, or {@code null}. */
    public static @Nullable MethodDecl enclosingMain(@Nullable PsiElement element) {
        MethodDecl method = PsiTreeUtil.getParentOfType(element, MethodDecl.class, false);
        return isMainEntryPoint(method) ? method : null;
    }

    public static boolean isMainEntryPoint(@Nullable MethodDecl method) {
        if (method == null) {
            return false;
        }
        MethodSignature signature = method.getMethodSignature();
        if (!"main".equals(signature.getIdentifier().getText())) {
            return false;
        }
        if (!hasStaticModifier(method.getMemberModifierList())) {
            return false;
        }
        return hasSingleStringArrayParameter(signature.getParameterList());
    }

    private static boolean hasStaticModifier(List<MemberModifier> modifiers) {
        for (MemberModifier modifier : modifiers) {
            if ("static".equals(modifier.getText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSingleStringArrayParameter(@Nullable ParameterList parameterList) {
        if (parameterList == null) {
            return false;
        }
        List<Parameter> parameters = parameterList.getParameterList();
        if (parameters.size() != 1) {
            return false;
        }
        return isStringArrayType(parameters.get(0).getTypeRef());
    }

    private static boolean isStringArrayType(TypeRef typeRef) {
        if (typeRef.getArraySuffixList().isEmpty()) {
            return false;
        }
        QualifiedName qualifiedName = typeRef.getQualifiedName();
        if (qualifiedName == null) {
            return false;
        }
        String simpleName = lastSegment(qualifiedName);
        return "String".equals(simpleName) || "java.lang.String".equals(qualifiedName.getText());
    }

    private static String lastSegment(QualifiedName qualifiedName) {
        List<Identifier> parts = qualifiedName.getIdentifierList();
        return parts.isEmpty() ? qualifiedName.getText() : parts.get(parts.size() - 1).getText();
    }

    /** Computes the fully-qualified generated Java class name for the class enclosing {@code element}, or {@code null}. */
    public static @Nullable String enclosingClassQualifiedName(@Nullable PsiElement element) {
        ClassDecl classDecl = PsiTreeUtil.getParentOfType(element, ClassDecl.class, false);
        if (classDecl == null) {
            return null;
        }
        String className = classDecl.getIdentifier().getText();
        String packageName = packageName(element);
        return packageName.isEmpty() ? className : packageName + "." + className;
    }

    /** Simple name of the class enclosing {@code element}, used as a run-configuration label. */
    public static @Nullable String enclosingClassSimpleName(@Nullable PsiElement element) {
        ClassDecl classDecl = PsiTreeUtil.getParentOfType(element, ClassDecl.class, false);
        return classDecl == null ? null : classDecl.getIdentifier().getText();
    }

    private static String packageName(PsiElement element) {
        PackageDecl packageDecl = PsiTreeUtil.findChildOfType(element.getContainingFile(), PackageDecl.class);
        return packageDecl == null ? "" : packageDecl.getQualifiedName().getText();
    }
}
