package dev.affogato.golden;

import java.util.ArrayDeque;
import java.util.Queue;

public class CollectionsGenericQueue {
    public String run() {
        final Queue<Task<String>> queue = new ArrayDeque<Task<String>>();
        queue.add(new Task<String>("steam"));
        final Task<String> task = queue.remove();
        return task.payload();
    }

}
