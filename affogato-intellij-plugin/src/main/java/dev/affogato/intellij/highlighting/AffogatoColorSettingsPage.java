package dev.affogato.intellij.highlighting;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import com.intellij.openapi.options.colors.ColorDescriptor;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import dev.affogato.intellij.AffogatoIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.Map;

public final class AffogatoColorSettingsPage implements ColorSettingsPage {
    private static final AttributesDescriptor[] DESCRIPTORS = new AttributesDescriptor[]{
            new AttributesDescriptor("Keyword", AffogatoSyntaxHighlighter.KEYWORD),
            new AttributesDescriptor("String", AffogatoSyntaxHighlighter.STRING),
            new AttributesDescriptor("Number", AffogatoSyntaxHighlighter.NUMBER),
            new AttributesDescriptor("Comment", AffogatoSyntaxHighlighter.COMMENT),
            new AttributesDescriptor("Class name", AffogatoSyntaxHighlighter.CLASS_NAME),
            new AttributesDescriptor("Type reference", AffogatoSyntaxHighlighter.TYPE_REF),
            new AttributesDescriptor("Function declaration", AffogatoSyntaxHighlighter.FUNCTION_DECL),
            new AttributesDescriptor("Function call", AffogatoSyntaxHighlighter.FUNCTION_CALL),
            new AttributesDescriptor("Parameter", AffogatoSyntaxHighlighter.PARAMETER),
            new AttributesDescriptor("Local variable", AffogatoSyntaxHighlighter.LOCAL_VARIABLE),
            new AttributesDescriptor("Instance field", AffogatoSyntaxHighlighter.INSTANCE_FIELD),
            new AttributesDescriptor("Annotation", AffogatoSyntaxHighlighter.ANNOTATION),
            new AttributesDescriptor("Enum constant", AffogatoSyntaxHighlighter.ENUM_CONST),
            new AttributesDescriptor("Valid string escape", AffogatoSyntaxHighlighter.VALID_STRING_ESCAPE),
            new AttributesDescriptor("Invalid string escape", AffogatoSyntaxHighlighter.INVALID_STRING_ESCAPE),
            new AttributesDescriptor("Braces", AffogatoSyntaxHighlighter.BRACES),
            new AttributesDescriptor("Parentheses", AffogatoSyntaxHighlighter.PARENTHESES),
            new AttributesDescriptor("Brackets", AffogatoSyntaxHighlighter.BRACKETS),
            new AttributesDescriptor("Operator sign", AffogatoSyntaxHighlighter.OPERATION_SIGN),
            new AttributesDescriptor("Bad character", AffogatoSyntaxHighlighter.BAD_CHARACTER),
    };

    private static final Map<String, TextAttributesKey> TAGS = Map.ofEntries(
            Map.entry("class", AffogatoSyntaxHighlighter.CLASS_NAME),
            Map.entry("type", AffogatoSyntaxHighlighter.TYPE_REF),
            Map.entry("func", AffogatoSyntaxHighlighter.FUNCTION_DECL),
            Map.entry("call", AffogatoSyntaxHighlighter.FUNCTION_CALL),
            Map.entry("param", AffogatoSyntaxHighlighter.PARAMETER),
            Map.entry("local", AffogatoSyntaxHighlighter.LOCAL_VARIABLE),
            Map.entry("field", AffogatoSyntaxHighlighter.INSTANCE_FIELD),
            Map.entry("anno", AffogatoSyntaxHighlighter.ANNOTATION),
            Map.entry("enum", AffogatoSyntaxHighlighter.ENUM_CONST),
            Map.entry("esc", AffogatoSyntaxHighlighter.VALID_STRING_ESCAPE),
            Map.entry("badesc", AffogatoSyntaxHighlighter.INVALID_STRING_ESCAPE)
    );

    @Override
    public @Nullable Icon getIcon() {
        return AffogatoIcons.FILE;
    }

    @Override
    public @NotNull SyntaxHighlighter getHighlighter() {
        return new AffogatoSyntaxHighlighter();
    }

    @Override
    public @NotNull String getDemoText() {
        return """
                package dev.affogato.samples

                // Coffee drinks
                enum <enum>DrinkSize</enum> { SMALL, MEDIUM, LARGE }

                @<anno>Deprecated</anno>
                class <class>App</class> {
                    static func <func>main</func>(<param>args</param>: <type>String</type>[]) {
                        let <local>person</local> = <type>Person</type>(name = "Affogato", age = 1)
                        <call>println</call>(<local>person</local>.<field>name</field>)
                        <call>println</call>(<local>person</local>.<call>greeting</call>(prefix = "Hola\\n"))
                        let <local>sizes</local> = <type>List</type><<type>String</type>>()
                        for <local>size</local> in <local>sizes</local> {
                            <call>println</call>("size: \\t\\"\\u00BB\\" <badesc>\\q</badesc>")
                        }
                        if <local>person</local>.<field>name</field>.<call>length</call>() > 0 {
                            <call>println</call>("ready")
                        }
                    }
                }

                func <type>String</type>.<func>shout</func>(): <type>String</type> {
                    return this + "!"
                }
                """;
    }

    @Override
    public @Nullable Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() {
        return TAGS;
    }

    @Override
    public AttributesDescriptor @NotNull [] getAttributeDescriptors() {
        return DESCRIPTORS;
    }

    @Override
    public ColorDescriptor @NotNull [] getColorDescriptors() {
        return ColorDescriptor.EMPTY_ARRAY;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Affogato";
    }
}
