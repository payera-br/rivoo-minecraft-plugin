plugins {
    id("com.gradleup.shadow")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    implementation(project(":core"))
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.116.3")
    testImplementation(testFixtures(project(":core")))
}

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("rivoo-minecraft-paper")
    archiveClassifier.set("")
    relocate("com.google.zxing", "dev.rivoo.minecraft.libs.zxing")
    relocate("com.google.gson", "dev.rivoo.minecraft.libs.gson")
}

tasks.jar { enabled = false }
tasks.build { dependsOn(tasks.named("shadowJar")) }
