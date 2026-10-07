pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral(); maven("https://jitpack.io") }
}
rootProject.name = "utah-bus-stop-scout"
include(":app")
// Use an explicit SDK checkout when supplied. In the author's multi-repo workspace, discover the
// sibling SDK checkout automatically so Android Studio's ordinary Build action sees APIs newer
// than the last published artifact. A clean clone can pass -PsdkCheckout=/path/to/sdk/kotlin.
val sdkCheckout = providers.gradleProperty("sdkCheckout").orNull
    ?: file("../maproulette-mobile-sdk/kotlin").takeIf { it.resolve("build.gradle.kts").isFile }?.path
sdkCheckout?.let(::includeBuild)
