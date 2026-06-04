package dev.affogato.golden;

public class CollectionsGenericMapValues {
    public String run() {
        java.util.HashMap<String,Score<Integer>> scores = new java.util.HashMap<String, Score<Integer>>();
        scores.put("coffee", new Score<Integer>("shots", 2));
        final Score<Integer> score = scores.get("coffee");
        return score.label() + score.value();
    }

}
