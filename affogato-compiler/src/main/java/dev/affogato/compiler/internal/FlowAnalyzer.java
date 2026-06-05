package dev.affogato.compiler.internal;

import dev.affogato.compiler.AffogatoDiagnostic;
import dev.affogato.compiler.parser.AffogatoParser;

import java.nio.file.Path;
import java.util.List;

final class FlowAnalyzer {
    private final List<AffogatoDiagnostic> diagnostics;

    FlowAnalyzer(List<AffogatoDiagnostic> diagnostics) {
        this.diagnostics = diagnostics;
    }

    boolean blockExits(AffogatoParser.BlockContext block) {
        List<AffogatoParser.StatementContext> statements = block.statement();
        for (int index = statements.size() - 1; index >= 0; index--) {
            AffogatoParser.StatementContext statement = statements.get(index);
            if (isPureSeparator(statement)) {
                continue;
            }
            return statementExits(statement);
        }
        return false;
    }

    /**
     * A {@code statement} matches the bare {@code separators} alternative (a blank line or lone semicolon)
     * only when it has no real child. Real statements such as {@code block}, {@code tryStatement} and
     * {@code switchStatement} can carry a trailing {@code separators?}, so checking {@code separators() != null}
     * alone wrongly skips them when they are the last statement of a block.
     */
    boolean isPureSeparator(AffogatoParser.StatementContext statement) {
        return statement.separators() != null
                && statement.block() == null
                && statement.guardStatement() == null
                && statement.ifStatement() == null
                && statement.forStatement() == null
                && statement.whileStatement() == null
                && statement.tryStatement() == null
                && statement.switchStatement() == null
                && statement.returnStatement() == null
                && statement.throwStatement() == null
                && statement.breakStatement() == null
                && statement.continueStatement() == null
                && statement.localVarDecl() == null
                && statement.expressionStatement() == null;
    }

    boolean blockStopsControl(AffogatoParser.BlockContext block) {
        List<AffogatoParser.StatementContext> statements = block.statement();
        for (int index = statements.size() - 1; index >= 0; index--) {
            AffogatoParser.StatementContext statement = statements.get(index);
            if (isPureSeparator(statement)) {
                continue;
            }
            return statementStopsControl(statement);
        }
        return false;
    }

    boolean statementExits(AffogatoParser.StatementContext statement) {
        if (statement.returnStatement() != null || statement.throwStatement() != null) {
            return true;
        }
        if (statement.block() != null) {
            return blockExits(statement.block());
        }
        if (statement.ifStatement() != null) {
            return ifExits(statement.ifStatement());
        }
        if (statement.tryStatement() != null) {
            return tryExits(statement.tryStatement());
        }
        if (statement.whileStatement() != null) {
            return whileExits(statement.whileStatement());
        }
        return false;
    }

    boolean statementStopsControl(AffogatoParser.StatementContext statement) {
        if (statement.returnStatement() != null
                || statement.throwStatement() != null
                || statement.breakStatement() != null
                || statement.continueStatement() != null) {
            return true;
        }
        if (statement.block() != null) {
            return blockStopsControl(statement.block());
        }
        if (statement.ifStatement() != null) {
            return ifStopsControl(statement.ifStatement());
        }
        if (statement.tryStatement() != null) {
            return tryStopsControl(statement.tryStatement());
        }
        return false;
    }

    private boolean whileExits(AffogatoParser.WhileStatementContext whileStatement) {
        String condText = whileStatement.condition().getText().trim()
                .replaceAll("[()\\s]", "");
        return condText.equals("true") && blockExits(whileStatement.block());
    }

    private boolean tryExits(AffogatoParser.TryStatementContext tryStatement) {
        if (tryStatement.finallyClause() != null && blockExits(tryStatement.finallyClause().block())) {
            return true;
        }
        if (!blockExits(tryStatement.block())) {
            return false;
        }
        return tryStatement.catchClause().stream().allMatch(clause -> blockExits(clause.block()));
    }

    private boolean ifExits(AffogatoParser.IfStatementContext ifStatement) {
        if (ifStatement.ELSE() == null || ifStatement.block().isEmpty()) {
            return false;
        }
        boolean thenExits = blockExits(ifStatement.block(0));
        boolean elseExits;
        if (ifStatement.ifStatement() != null) {
            elseExits = ifExits(ifStatement.ifStatement());
        } else if (ifStatement.block().size() > 1) {
            elseExits = blockExits(ifStatement.block(1));
        } else {
            elseExits = false;
        }
        return thenExits && elseExits;
    }

    private boolean tryStopsControl(AffogatoParser.TryStatementContext tryStatement) {
        if (tryStatement.finallyClause() != null && blockStopsControl(tryStatement.finallyClause().block())) {
            return true;
        }
        if (!blockStopsControl(tryStatement.block())) {
            return false;
        }
        return tryStatement.catchClause().stream().allMatch(clause -> blockStopsControl(clause.block()));
    }

    private boolean ifStopsControl(AffogatoParser.IfStatementContext ifStatement) {
        if (ifStatement.ELSE() == null || ifStatement.block().isEmpty()) {
            return false;
        }
        boolean thenStops = blockStopsControl(ifStatement.block(0));
        boolean elseStops;
        if (ifStatement.ifStatement() != null) {
            elseStops = ifStopsControl(ifStatement.ifStatement());
        } else if (ifStatement.block().size() > 1) {
            elseStops = blockStopsControl(ifStatement.block(1));
        } else {
            elseStops = false;
        }
        return thenStops && elseStops;
    }

    void checkUnreachable(Path sourceFile, AffogatoParser.BlockContext block) {
        boolean exited = false;
        for (AffogatoParser.StatementContext stmt : block.statement()) {
            if (isPureSeparator(stmt)) {
                continue;
            }
            if (exited) {
                int line = stmt.getStart().getLine();
                int column = stmt.getStart().getCharPositionInLine() + 1;
                int length = statementHighlightLength(stmt);
                diagnostics.add(new AffogatoDiagnostic(
                        AffogatoDiagnostic.Severity.WARNING,
                        "AFFOGATO_UNREACHABLE",
                        "Unreachable statement.",
                        sourceFile,
                        line,
                        column,
                        length
                ));
            }
            if (statementStopsControl(stmt)) {
                exited = true;
            }
        }
    }

    private static int statementHighlightLength(AffogatoParser.StatementContext stmt) {
        if (stmt.getStart() == null || stmt.getStop() == null) {
            return 1;
        }
        if (stmt.getStart().getLine() == stmt.getStop().getLine()) {
            return Math.max(1, stmt.getStop().getCharPositionInLine() - stmt.getStart().getCharPositionInLine() + 1);
        }
        return Math.max(1, stmt.getStart().getText().length());
    }
}
