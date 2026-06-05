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
    @Override
    public @NotNull FormattingModel createModel(@NotNull FormattingContext formattingContext) {
        CodeStyleSettings settings = formattingContext.getCodeStyleSettings();
        AffogatoBlock root = new AffogatoBlock(
                formattingContext.getNode(),
                null,
                Indent.getNoneIndent(),
                spacingBuilder(settings)
        );
        return FormattingModelProvider.createFormattingModelForPsiFile(formattingContext.getContainingFile(), root, settings);
    }

    private static SpacingBuilder spacingBuilder(CodeStyleSettings settings) {
        TokenSet binaryOperators = TokenSet.create(
                AffogatoTypes.ASSIGN,
                AffogatoTypes.PLUS,
                AffogatoTypes.MINUS,
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
                .before(AffogatoTypes.COMMA).spaces(0)
                .after(AffogatoTypes.COMMA).spaces(1)
                .before(AffogatoTypes.COLON).spaces(0)
                .after(AffogatoTypes.COLON).spaces(1)
                .after(AffogatoTypes.LBRACE).lineBreakInCode()
                .before(AffogatoTypes.RBRACE).lineBreakInCode()
                .after(AffogatoTypes.SEMI).lineBreakInCode();
    }

    private static final class AffogatoBlock extends AbstractBlock {
        private final SpacingBuilder spacingBuilder;
        private final Indent indent;

        private AffogatoBlock(ASTNode node, @Nullable Alignment alignment, Indent indent, SpacingBuilder spacingBuilder) {
            super(node, null, alignment);
            this.indent = indent;
            this.spacingBuilder = spacingBuilder;
        }

        @Override
        protected List<Block> buildChildren() {
            List<Block> blocks = new ArrayList<>();
            ASTNode child = myNode.getFirstChildNode();
            while (child != null) {
                if (child.getTextLength() > 0) {
                    blocks.add(new AffogatoBlock(child, null, childIndent(child), spacingBuilder));
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
            return spacingBuilder.getSpacing(this, child1, child2);
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
