plugins {
    id("build-logic.android.library.common")
}

android {
    namespace = "com.mocharealm.accompanist.lyrics.core"
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}
