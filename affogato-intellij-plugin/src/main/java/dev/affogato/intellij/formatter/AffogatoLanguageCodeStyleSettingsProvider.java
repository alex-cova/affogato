package dev.affogato.intellij.formatter;

import com.intellij.lang.Language;
import com.intellij.psi.codeStyle.CodeStyleSettingsCustomizable;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider;
import com.intellij.application.options.IndentOptionsEditor;
import com.intellij.application.options.SmartIndentOptionsEditor;
import dev.affogato.intellij.AffogatoLanguage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Surfaces Affogato under Settings → Editor → Code Style, providing a live
 * preview, configurable indentation, spacing, and blank-line options that the
 * {@link AffogatoFormattingModelBuilder} reads back through
 * {@link CommonCodeStyleSettings}.
 */
public final class AffogatoLanguageCodeStyleSettingsProvider extends LanguageCodeStyleSettingsProvider {
    @Override
    public @NotNull Language getLanguage() {
        return AffogatoLanguage.INSTANCE;
    }

    @Override
    public @Nullable IndentOptionsEditor getIndentOptionsEditor() {
        return new SmartIndentOptionsEditor();
    }

    @Override
    public void customizeDefaults(
            @NotNull CommonCodeStyleSettings commonSettings,
            @NotNull CommonCodeStyleSettings.IndentOptions indentOptions) {
        indentOptions.INDENT_SIZE = 4;
        indentOptions.CONTINUATION_INDENT_SIZE = 8;
        indentOptions.TAB_SIZE = 4;
        indentOptions.USE_TAB_CHARACTER = false;
        commonSettings.BLANK_LINES_AROUND_METHOD = 1;
        commonSettings.KEEP_BLANK_LINES_IN_DECLARATIONS = 1;
        commonSettings.KEEP_BLANK_LINES_IN_CODE = 1;
    }

    @Override
    public void customizeSettings(
            @NotNull CodeStyleSettingsCustomizable consumer,
            @NotNull SettingsType settingsType) {
        switch (settingsType) {
            case SPACING_SETTINGS -> consumer.showStandardOptions(
                    "SPACE_AROUND_ASSIGNMENT_OPERATORS",
                    "SPACE_AROUND_LOGICAL_OPERATORS",
                    "SPACE_AROUND_EQUALITY_OPERATORS",
                    "SPACE_AROUND_RELATIONAL_OPERATORS",
                    "SPACE_AROUND_ADDITIVE_OPERATORS",
                    "SPACE_AROUND_MULTIPLICATIVE_OPERATORS",
                    "SPACE_AFTER_COMMA",
                    "SPACE_BEFORE_COMMA");
            case BLANK_LINES_SETTINGS -> consumer.showStandardOptions(
                    "KEEP_BLANK_LINES_IN_DECLARATIONS",
                    "KEEP_BLANK_LINES_IN_CODE",
                    "BLANK_LINES_AROUND_METHOD");
            case INDENT_SETTINGS -> consumer.showStandardOptions(
                    "INDENT_SIZE",
                    "CONTINUATION_INDENT_SIZE",
                    "TAB_SIZE",
                    "USE_TAB_CHARACTER");
            default -> { /* no extra options for other categories */ }
        }
    }

    @Override
    public @NotNull String getCodeSample(@NotNull SettingsType settingsType) {
        return """
                package com.example.demo

                class Buffer {
                    let json: String
                    var index: int

                    init(json: String) {
                        this.json = json
                        this.index = 0
                    }

                    func next(): boolean {
                        if index + 1 >= json.length() {
                            return false
                        } else {
                            index = index + 1
                            return true
                        }
                    }
                }
                """;
    }
}
