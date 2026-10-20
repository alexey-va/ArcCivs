pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.rus-crafting.ru/grocermc/") {
            content { includeGroup("ru.ruscrafting.arc"); includeGroup("ru.ruscrafting") }
        }
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.codemc.io/repository/maven-public/") { content { includeGroup("com.github.retrooper") } }
        maven("https://jitpack.io") { content { includeGroup("com.github.angeschossen"); includeGroup("com.github.LoneDev6") } }
    }
}
rootProject.name = "ArcCivs"

// Explicit development substitution; normal builds resolve the pinned public release.
providers.gradleProperty("arcCoreDir").orNull?.let(::file)?.let { core ->
    require(core.resolve("settings.gradle.kts").isFile) { "arcCoreDir must point to an arc-core checkout" }
    includeBuild(core) {
        dependencySubstitution {
            listOf("arc-core", "arc-core-integration-testing", "arc-core-logging", "arc-core-menu", "arc-core-paper",
                "arc-core-paper-api", "arc-core-paper-menu", "arc-core-paper-testing", "arc-core-redis", "arc-core-sql", "arc-core-testing")
                .forEach { artifact -> substitute(module("ru.ruscrafting.arc:$artifact")).using(project(":$artifact")) }
        }
    }
}
