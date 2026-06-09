package dev.affogato.intellij.run;

import com.intellij.execution.BeforeRunTask;
import com.intellij.execution.RunManagerEx;
import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.LazyRunConfigurationProducer;
import com.intellij.execution.application.ApplicationConfiguration;
import com.intellij.execution.application.ApplicationConfigurationType;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings;
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemBeforeRunTask;
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiElement;
import dev.affogato.intellij.psi.MethodDecl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.gradle.execution.GradleBeforeRunTaskProvider;
import org.jetbrains.plugins.gradle.util.GradleConstants;

import java.util.List;

/**
 * Produces a standard Java {@link ApplicationConfiguration} targeting the generated class for an
 * Affogato {@code main} entry point, so the gutter Run icon executes the program just like Java's.
 *
 * <p>Because IntelliJ's own Java compiler cannot transpile {@code .aff} sources, the produced
 * configuration replaces the default "Build" before-launch step with a Gradle task that runs
 * {@code classes} (which chains {@code compileAffogato} -> {@code compileJava}). This makes the
 * gutter work regardless of the user's "Build and run using" delegation setting — without it a
 * native build would never generate the entry-point class and the launch fails with
 * {@code ClassNotFoundException}.
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
            attachGradleBuild(configuration, module);
        }
        sourceElement.set(main);
        return true;
    }

    /**
     * Replaces the configuration's before-launch steps with a single Gradle {@code classes} task for
     * the module's Gradle (sub)project, so the Affogato sources are transpiled and compiled by Gradle
     * before the application launches. No-op when the module is not part of an imported Gradle build.
     */
    private static void attachGradleBuild(@NotNull ApplicationConfiguration configuration, @NotNull Module module) {
        String externalProjectPath = ExternalSystemApiUtil.getExternalProjectPath(module);
        if (externalProjectPath == null) {
            return;
        }
        ExternalSystemBeforeRunTask gradleTask =
                new GradleBeforeRunTaskProvider(module.getProject()).createTask(configuration);
        if (gradleTask == null) {
            return;
        }
        ExternalSystemTaskExecutionSettings settings = gradleTask.getTaskExecutionSettings();
        settings.setExternalSystemIdString(GradleConstants.SYSTEM_ID.getId());
        settings.setExternalProjectPath(externalProjectPath);
        settings.setTaskNames(List.of("classes"));
        gradleTask.setEnabled(true);
        // Replace the default native "Build" step entirely — it cannot compile .aff sources.
        RunManagerEx.getInstanceEx(module.getProject())
                .setBeforeRunTasks(configuration, List.<BeforeRunTask>of(gradleTask));
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
