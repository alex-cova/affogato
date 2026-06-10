package dev.affogato.intellij.formatter;

import com.intellij.formatting.Alignment;
import com.intellij.formatting.Block;
import com.intellij.formatting.ChildAttributes;
import com.intellij.formatting.FormattingContext;
import com.intellij.formatting.FormattingModel;
import com.intellij.formatting.FormattingModelBuilder;
import com.intellij.formatting.FormattingModelProvider;
import com.intellij.formatting.Indent;
import com.intellij.formatting.Spacing;
import com.intellij.formatting.SpacingBuilder;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.TokenType;
import com.intellij.psi.formatter.common.AbstractBlock;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import dev.affogato.intellij.AffogatoLanguage;
import dev.affogato.intellij.psi.AffogatoTypes;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class AffogatoFormattingModelBuilder implements FormattingModelBuilder {
    private static final TokenSet TYPE_BODIES = TokenSet.create(
            AffogatoTypes.CLASS_BODY,
            AffogatoTypes.ENUM_BODY,
            AffogatoTypes.INTERFACE_BODY
    );

    // Brace-delimited bodies. The opening brace is the first leaf *inside* these
    // nodes, so a "space before {" rule must key on the body element itself.
    private static final TokenSet BRACE_BODIES = TokenSet.create(
            AffogatoTypes.BLOCK,
            AffogatoTypes.CLASS_BODY,
            AffogatoTypes.ENUM_BODY,
            AffogatoTypes.INTERFACE_BODY,
            AffogatoTypes.SWITCH_BODY,
            AffogatoTypes.TRAILING_CLOSURE
    );

    @Override
    public @NotNull FormattingModel createModel(@NotNull FormattingContext formattingContext) {
        CodeStyleSettings settings = formattingContext.getCodeStyleSettings();
        CommonCodeStyleSettings common = settings.getCommonSettings(AffogatoLanguage.INSTANCE);
        AffogatoBlock root = new AffogatoBlock(
                formattingContext.getNode(),
                null,
                Indent.getNoneIndent(),
                spacingBuilder(settings),
                common
        );
        return FormattingModelProvider.createFormattingModelForPsiFile(formattingContext.getContainingFile(), root, settings);
    }

    private static SpacingBuilder spacingBuilder(CodeStyleSettings settings) {
        // Unary minus shares the MINUS token with binary subtraction, so it is
        // intentionally excluded here; spacing around it is left as authored.
        TokenSet binaryOperators = TokenSet.create(
                AffogatoTypes.ASSIGN,
                AffogatoTypes.PLUS,
                AffogatoTypes.STAR,
                AffogatoTypes.SLASH,
                AffogatoTypes.PERCENT,
                AffogatoTypes.EQ,
                AffogatoTypes.NE,
                AffogatoTypes.LT,
                AffogatoTypes.LE,
                AffogatoTypes.GT,
                AffogatoTypes.GE,
                AffogatoTypes.AND,
                AffogatoTypes.OR,
                AffogatoTypes.ARROW
        );
        return new SpacingBuilder(settings, AffogatoLanguage.INSTANCE)
                .around(binaryOperators).spaces(1)
                .around(AffogatoTypes.ELSE_KEYWORD).spaces(1)
                .around(AffogatoTypes.CATCH_KEYWORD).spaces(1)
                .around(AffogatoTypes.FINALLY_KEYWORD).spaces(1)
                .around(AffogatoTypes.DOT).spaces(0)
                .before(AffogatoTypes.COMMA).spaces(0)
                .after(AffogatoTypes.COMMA).spaces(1)
                .before(AffogatoTypes.COLON).spaces(0)
                .after(AffogatoTypes.COLON).spaces(1)
                .after(AffogatoTypes.LPAREN).spaces(0)
                .before(AffogatoTypes.RPAREN).spaces(0)
                .before(BRACE_BODIES).spaces(1)
                .after(AffogatoTypes.LBRACE).lineBreakInCode()
                .before(AffogatoTypes.RBRACE).lineBreakInCode()
                .after(AffogatoTypes.SEMI).lineBreakInCode();
    }

    private static final class AffogatoBlock extends AbstractBlock {
        private final SpacingBuilder spacingBuilder;
        private final CommonCodeStyleSettings common;
        private final Indent indent;

        private AffogatoBlock(
                ASTNode node,
                @Nullable Alignment alignment,
                Indent indent,
                SpacingBuilder spacingBuilder,
                CommonCodeStyleSettings common) {
            super(node, null, alignment);
            this.indent = indent;
            this.spacingBuilder = spacingBuilder;
            this.common = common;
        }

        @Override
        protected List<Block> buildChildren() {
            List<Block> blocks = new ArrayList<>();
            ASTNode child = myNode.getFirstChildNode();
            while (child != null) {
                // Whitespace is owned by the formatter (via Spacing); building blocks
                // over it corrupts offset/indent computation, so skip those nodes.
                if (child.getElementType() != TokenType.WHITE_SPACE && child.getTextLength() > 0) {
                    blocks.add(new AffogatoBlock(child, null, childIndent(child), spacingBuilder, common));
                }
                child = child.getTreeNext();
            }
            return blocks;
        }

        private Indent childIndent(ASTNode child) {
            IElementType parentType = myNode.getElementType();
            IElementType childType = child.getElementType();
            if (childType == AffogatoTypes.RBRACE) {
                return Indent.getNoneIndent();
            }
            if (parentType == AffogatoTypes.BLOCK
                    || parentType == AffogatoTypes.CLASS_BODY
                    || parentType == AffogatoTypes.ENUM_BODY
                    || parentType == AffogatoTypes.INTERFACE_BODY
                    || parentType == AffogatoTypes.SWITCH_BODY
                    || parentType == AffogatoTypes.TRAILING_CLOSURE) {
                return Indent.getNormalIndent();
            }
            return Indent.getNoneIndent();
        }

        @Override
        public @Nullable Spacing getSpacing(@Nullable Block child1, @NotNull Block child2) {
            if (child1 instanceof AffogatoBlock first && child2 instanceof AffogatoBlock second) {
                IElementType firstType = first.myNode.getElementType();
                IElementType secondType = second.myNode.getElementType();

                // Empty braces: don't inject a line break into "{}", but preserve
                // one the author wrote (keepLineBreaks) rather than collapsing it.
                if (firstType == AffogatoTypes.LBRACE && secondType == AffogatoTypes.RBRACE) {
                    return Spacing.createSpacing(0, 0, 0, true, 0);
                }

                // Between members of a type body keep them on separate lines and
                // collapse runs of blank lines down to the configured maximum.
                if (TYPE_BODIES.contains(myNode.getElementType())
                        && isMember(firstType)
                        && isMember(secondType)) {
                    return Spacing.createSpacing(0, 0, 1, true, common.KEEP_BLANK_LINES_IN_DECLARATIONS);
                }
            }
            return spacingBuilder.getSpacing(this, child1, child2);
        }

        private static boolean isMember(IElementType type) {
            // Members of a type body are wrapped (CLASS_MEMBER / INTERFACE_MEMBER /
            // ENUM_CONSTANT_LIST, …); anything that is not a brace qualifies.
            return type != AffogatoTypes.LBRACE && type != AffogatoTypes.RBRACE;
        }

        @Override
        public boolean isLeaf() {
            return myNode.getFirstChildNode() == null;
        }

        @Override
        public Indent getIndent() {
            return indent;
        }

        @Override
        public @NotNull ChildAttributes getChildAttributes(int newChildIndex) {
            return new ChildAttributes(Indent.getNormalIndent(), null);
        }

        @Override
        public @NotNull TextRange getTextRange() {
            return myNode.getTextRange();
        }
    }
}
