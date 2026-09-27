plugins { kotlin("jvm") version "2.4.20" apply false }
allprojects { group = "org.logh7"; version = "0.1.0-SNAPSHOT"; repositories { mavenCentral() } }
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> { jvmToolchain(25) }
    dependencies { "testImplementation"(kotlin("test")); "testImplementation"("org.junit.jupiter:junit-jupiter:5.13.4"); "testRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.13.4") }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}
