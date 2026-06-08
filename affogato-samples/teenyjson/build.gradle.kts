plugins {
    application
    id("dev.affogato")
}

application {
    mainClass.set("com.alexcova.teenyjson.App")
}

affogato {
    sourceDirs.from("src/main/affogato")
}
