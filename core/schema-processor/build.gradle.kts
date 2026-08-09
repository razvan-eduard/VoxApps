plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    implementation(project(":core:schema-annotations"))
    implementation("com.google.devtools.ksp:symbol-processing-api:2.3.11")
}
