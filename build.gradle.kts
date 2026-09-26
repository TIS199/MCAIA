plugins {
    java
    id("com.gradleup.shadow") version "9.3.2"
}

group = "com.mcaia.plugin"
version = "1.0.5"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")                                // Vault
    maven("https://repo.opencollab.dev/main/")                 // Floodgate / Geyser
}

dependencies {
    // Paper API (provided at runtime by the server — do NOT shade)
    compileOnly("io.papermc.paper:paper-api:26.2.build.124-stable")

    // Optional plugin integrations (provided at runtime if installed — do NOT shade)
    // Vault via JitPack
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    compileOnly("net.luckperms:api:5.4")
    compileOnly("org.geysermc.floodgate:api:2.2.3-SNAPSHOT")

    // Bundled dependencies — shaded into the final JAR
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // bStats
    implementation("org.bstats:bstats-bukkit:3.2.1")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)   // Paper 26.2 requires Java 25
}

// Disable the plain jar; shadowJar is the real artifact
tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveBaseName.set("MCAIA")

    relocate("org.bstats", "${project.group}.lib.bstats")

    // Strip signing metadata that breaks fat JARs
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

// Quick deploy: builds and copies the JAR into the Paper plugins folder
val copyToServer = tasks.register<Copy>("copyToServer") {
    dependsOn(tasks.named("shadowJar"))
    from(tasks.named("shadowJar"))
    into(file("../server/plugins"))
}
