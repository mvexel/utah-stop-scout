plugins { id("com.android.application") }

val stageClientId = providers.gradleProperty("busStopStageClientId").orElse("")
val prodClientId = providers.gradleProperty("busStopProdClientId").orElse("")
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "org.osmutah.utahbusstop"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.osmutah.utahbusstop"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["appAuthRedirectScheme"] = "org.osmutah.utahbusstop"
        buildConfigField("String", "STAGE_CLIENT_ID", quoted(""))
        buildConfigField("String", "PROD_CLIENT_ID", quoted(""))
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        getByName("debug") {
            buildConfigField("String", "STAGE_CLIENT_ID", quoted(stageClientId.get()))
            buildConfigField("String", "PROD_CLIENT_ID", quoted(prodClientId.get()))
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
    implementation("net.openid:appauth:0.11.1")
    implementation("androidx.browser:browser:1.9.0")
    implementation("com.github.mvexel:maproulette-mobile-sdk:0.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.maplibre.gl:android-sdk:11.13.5")
    testImplementation("junit:junit:4.13.2")
}
