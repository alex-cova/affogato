plugins {
    application
    id("dev.affogato")
}

application {
    mainClass.set("dev.affogato.samples.App")
}

affogato {
    sourceDirs.from("src/main/affogato")
}
