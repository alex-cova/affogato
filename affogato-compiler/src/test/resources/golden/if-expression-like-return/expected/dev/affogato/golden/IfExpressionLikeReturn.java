package dev.affogato.golden;

public class IfExpressionLikeReturn {
    public CharSequence label(int value) {
        if (value > 0) {
            return "positive";
        } else if (value == 0) {
    return new StringBuilder("zero");
} else {
    return "negative";
}
    }

}
