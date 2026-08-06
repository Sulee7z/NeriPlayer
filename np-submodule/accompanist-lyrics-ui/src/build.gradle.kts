plugins {
    id("build-logic.android.library.common")
    id("build-logic.android.compose")
}

android {
    namespace = "com.mocharealm.accompanist.lyrics.ui"
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.foundation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.animation)
    implementation(libs.androidx.ui.graphics)

    implementation(project(":accompanist-lyrics-core"))

    testImplementation(libs.junit)
}
