package dev.affogato.golden;

public class InstanceOverloads {
    public String run() {
        final InstanceRouter router = new InstanceRouter();
        final String name = router.route("name");
        final String count = router.route(3);
        return name + count;
    }

}
