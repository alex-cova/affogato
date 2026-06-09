package dev.affogato.intellij.run;

import com.intellij.execution.lineMarker.ExecutorAction;
import com.intellij.execution.lineMarker.RunLineMarkerContributor;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.Nullable;

/**
 * Shows the standard green "Run" gutter icon on the {@code main} entry point of Affogato files,
 * matching the experience Java provides on {@code public static void main}.
 */
public final class AffogatoRunLineMarkerContributor extends RunLineMarkerContributor {
    @Override
    public @Nullable Info getInfo(PsiElement element) {
        if (AffogatoMainUtil.mainFromNameLeaf(element) == null) {
            return null;
        }
        AnAction[] actions = ExecutorAction.getActions(0);
        return new Info(
                AllIcons.RunConfigurations.TestState.Run,
                actions,
                psi -> "Run Affogato application"
        );
    }
}
