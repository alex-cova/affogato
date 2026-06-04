package dev.affogato.golden;

public class StringLiteralsWithUnsupportedTokens {
    public String describe() {
        String safeCallMsg = "Affogato does not support ?. (safe-call operator)";
        String elvisMsg = "Affogato does not support ?: (Elvis operator)";
        String assertMsg = "Affogato does not support !! (not-null assertion)";
        return safeCallMsg + " | " + elvisMsg + " | " + assertMsg;
    }

}
