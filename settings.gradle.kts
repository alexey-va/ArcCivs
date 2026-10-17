pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.rus-crafting.ru/grocermc/") { content { includeGroup("ru.ruscrafting.arc") } }
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://maven.citizensnpcs.co/repo") { content { includeGroup("net.citizensnpcs") } }
        maven("https://repo.codemc.io/repository/maven-public/") { content { includeGroup("com.github.retrooper") } }
        maven("https://jitpack.io") { content { includeGroup("com.github.angeschossen") } }
    }
}
rootProject.name = "ArcCivs"
