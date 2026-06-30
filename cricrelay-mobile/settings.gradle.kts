pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "cricrelay-mobile"

include(":shared")
include(":app")
include(":core:ui")
include(":core:database")
include(":feature:auth")
include(":feature:home")
include(":feature:studio")
include(":feature:scoring")
include(":streaming")

project(":app").projectDir = file("android/app")
project(":core:ui").projectDir = file("android/core/ui")
project(":core:database").projectDir = file("android/core/database")
project(":feature:auth").projectDir = file("android/feature/auth")
project(":feature:home").projectDir = file("android/feature/home")
project(":feature:studio").projectDir = file("android/feature/studio")
project(":feature:scoring").projectDir = file("android/feature/scoring")
project(":streaming").projectDir = file("android/streaming")
