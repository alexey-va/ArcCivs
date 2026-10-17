plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.0"
}
group = "ru.ruscrafting"
version = "0.1.2"
description = "Persistent player settlements and working citizens for RusCrafting"
val arcCorePluginVersion = "0.1.8"
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
kotlin { jvmToolchain(25) }
dependencies {
    implementation(kotlin("stdlib"))
    implementation("ru.ruscrafting.arc:arc-core:2.7.32")
    implementation("ru.ruscrafting.arc:arc-core-logging:2.7.32")
    implementation("ru.ruscrafting.arc:arc-core-menu:2.7.32")
    implementation("ru.ruscrafting.arc:arc-core-paper:2.7.32")
    implementation("ru.ruscrafting.arc:arc-core-paper-menu:2.7.32")
    implementation("com.google.code.gson:gson:2.11.0")
    compileOnly("ru.ruscrafting.arc:arc-core-paper-api:2.7.32")
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.github.retrooper:packetevents-spigot:2.12.1") { exclude(group = "io.netty") }
    compileOnly("net.citizensnpcs:citizens-main:2.0.42-SNAPSHOT") { isTransitive = false }
    compileOnly("com.github.angeschossen:LandsAPI:6.26.18")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:2.7.32")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-api:2.7.32")
    testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core:6.0.7")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("com.github.retrooper:packetevents-spigot:2.12.1") { exclude(group = "io.netty") }
    testImplementation("net.citizensnpcs:citizens-main:2.0.42-SNAPSHOT") { isTransitive = false }
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")
    maxParallelForks = 1
}
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
tasks.jar { archiveClassifier.set("plain") }
val verificationHost = configurations.detachedConfiguration(
    dependencies.create("ru.ruscrafting:ArcCore:$arcCorePluginVersion"),
).apply { isTransitive = false }
val prepareHostArtifact by tasks.registering(Copy::class) {
    from({ verificationHost.singleFile }) { rename { "ArcCore.jar" } }
    into(layout.buildDirectory.dir("verification"))
}
tasks.shadowJar {
    dependsOn(prepareHostArtifact)
    archiveClassifier.set("")
    mergeServiceFiles()
    relocate("com.google.gson", "ru.arc.civs.internal.gson")
    exclude("ru/arc/paper/api/**")
    exclude("ru/arc/paper/sql/PaperSqlServiceProvider*.class")
    exclude("ru/arc/paper/sql/SharedPaperSqlService*.class")
    exclude("ru/arc/paper/redis/PaperRedisServiceProvider*.class")
    exclude("ru/arc/paper/redis/SharedPaperRedisService*.class")
    exclude("ru/arc/paper/metrics/PaperArcMetricsProvider*.class")
    exclude("ru/arc/paper/packet/PaperVisualPacketRuntime*.class")
    exclude("ru/arc/paper/schematics/**")
    exclude("org/bukkit/**", "io/papermc/**", "net/kyori/adventure/**", "org/slf4j/**")
}
tasks.assemble { dependsOn(tasks.shadowJar) }
