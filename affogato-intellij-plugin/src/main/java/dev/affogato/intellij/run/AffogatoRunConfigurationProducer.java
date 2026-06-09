package dev.affogato.intellij.run;

import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.application.ApplicationConfiguration;
import com.intellij.execution.application.ApplicationConfigurationType;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.actions.LazyRunConfigurationProducer;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiElement;
import dev.affogato.intellij.psi.MethodDecl;
import org.jetbrains.annotations.NotNull;

/**
 * Produces a standard Java {@link ApplicationConfiguration} targeting the generated class for an
 * Affogato {@code main} entry point, so the gutter Run icon executes the program just like Java's.
 */
public final class AffogatoRunConfigurationProducer extends LazyRunConfigurationProducer<ApplicationConfiguration> {
    @Override
    public @NotNull ConfigurationFactory getConfigurationFactory() {
        return ApplicationConfigurationType.getInstance().getConfigurationFactories()[0];
    }

    @Override
    protected boolean setupConfigurationFromContext(@NotNull ApplicationConfiguration configuration,
                                                     @NotNull ConfigurationContext context,
                                                     @NotNull Ref<PsiElement> sourceElement) {
        PsiElement location = context.getPsiLocation();
        MethodDecl main = AffogatoMainUtil.enclosingMain(location);
        if (main == null) {
            return false;
        }
        String qualifiedName = AffogatoMainUtil.enclosingClassQualifiedName(main);
        if (qualifiedName == null) {
            return false;
        }
        configuration.setMainClassName(qualifiedName);
        String simpleName = AffogatoMainUtil.enclosingClassSimpleName(main);
        configuration.setName(simpleName != null ? simpleName : qualifiedName);
        Module module = context.getModule();
        if (module != null) {
            configuration.setModule(module);
        }
        sourceElement.set(main);
        return true;
    }

    @Override
    public boolean isConfigurationFromContext(@NotNull ApplicationConfiguration configuration,
                                              @NotNull ConfigurationContext context) {
        MethodDecl main = AffogatoMainUtil.enclosingMain(context.getPsiLocation());
        if (main == null) {
            return false;
        }
        String qualifiedName = AffogatoMainUtil.enclosingClassQualifiedName(main);
        return qualifiedName != null && qualifiedName.equals(configuration.getMainClassName());
    }
}
