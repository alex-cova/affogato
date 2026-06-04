package dev.affogato.golden;

public class RunnableClosures {
    public static void runTask(Runnable task) {
        task.run();
    }

    public String run() {
        RunnableClosures.runTask(() -> {
System.out.println("done");
});
        return "ok";
    }

}
