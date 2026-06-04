package dev.affogato.golden;

public class CollectionsGenericSet {
    public int run() {
        java.util.HashSet<Tag<String>> tags = new java.util.HashSet<Tag<String>>();
        tags.add(new Tag<String>("hot"));
        tags.add(new Tag<String>("iced"));
        return tags.size();
    }

}
