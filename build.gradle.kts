plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.0"
}
group = "ru.ruscrafting"
version = "0.1.155"
description = "Persistent player settlements and working citizens for RusCrafting"
val arcCorePluginVersion = "0.1.27"
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
kotlin { jvmToolchain(25) }
val integrationTestSourceSet = sourceSets.create("integrationTest") {
    kotlin.srcDir("src/integrationTest/kotlin")
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}
kotlin.target.compilations.getByName("integrationTest").associateWith(kotlin.target.compilations.getByName("main"))
configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])
dependencies {
    implementation(kotlin("stdlib"))
    implementation("ru.ruscrafting.arc:arc-core:2.7.81")
    implementation("ru.ruscrafting.arc:arc-core-logging:2.7.81")
    implementation("ru.ruscrafting.arc:arc-core-menu:2.7.81")
    implementation("ru.ruscrafting.arc:arc-core-paper:2.7.81")
    implementation("ru.ruscrafting.arc:arc-core-paper-menu:2.7.81")
    implementation("ru.ruscrafting.arc:arc-core-sql:2.7.81")
    implementation("com.google.code.gson:gson:2.11.0")
    compileOnly("ru.ruscrafting.arc:arc-core-paper-api:2.7.81")
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.github.retrooper:packetevents-spigot:2.12.1") { exclude(group = "io.netty") }
    compileOnly("com.github.angeschossen:LandsAPI:6.26.18")
    compileOnly("ru.ruscrafting:trails-api:2.3.22")
    compileOnly("com.github.LoneDev6:api-itemsadder:3.6.1")
    compileOnly("net.luckperms:api:5.5")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:2.7.81")
    testImplementation("ru.ruscrafting.arc:arc-core-integration-testing:2.7.81")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-api:2.7.81")
    testImplementation("ru.ruscrafting:trails-api:2.3.22")
    testImplementation("com.github.LoneDev6:api-itemsadder:3.6.1")
    testImplementation("net.luckperms:api:5.5")
    testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core:6.0.7")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("com.github.retrooper:packetevents-spigot:2.12.1") { exclude(group = "io.netty") }
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
val requestedTestForks = providers.gradleProperty("civsTestForks").orElse("1").get().toInt()
require(requestedTestForks in 1..2) { "civsTestForks must be 1 or 2" }
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "2g"
    jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")
    maxParallelForks = requestedTestForks
    // MockBukkit/MockK retain JVM state; isolate suites without increasing the heap.
    forkEvery = 1
}
tasks.register<Test>("integrationTest") {
    description = "Runs disposable MySQL network-authority tests."
    group = "verification"
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    shouldRunAfter(tasks.test)
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
    exclude("ru/arc/paper/display/PaperDisplayPassengerServiceProvider*.class")
    exclude("ru/arc/paper/runtime/PaperNativeCleanupServiceProvider*.class")
    exclude("ru/arc/paper/scene/PaperSceneObservationServiceProvider*.class")
    exclude("ru/arc/paper/scene/PaperSceneEntityInfoServiceProvider*.class")
    exclude("ru/arc/paper/schematics/**")
    exclude("org/bukkit/**", "io/papermc/**", "net/kyori/adventure/**", "org/slf4j/**")
}
tasks.assemble { dependsOn(tasks.shadowJar) }
