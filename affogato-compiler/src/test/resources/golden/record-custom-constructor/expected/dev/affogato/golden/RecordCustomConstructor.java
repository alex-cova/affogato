package dev.affogato.golden;

public class RecordCustomConstructor {
    public String run() {
        final RecordUserName user = new RecordUserName(" affogato ");
        return user.normalized();
    }

}
