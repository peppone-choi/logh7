plugins { kotlin("jvm") }
dependencies {
implementation(project(":engine"))
// The live backup copies the definitions DB with the driver's COPY API.
implementation("org.postgresql:postgresql:42.7.14")
testImplementation(project(":gateway"))
testImplementation(project(":protocol"))
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}
tasks.test { doFirst { systemProperty("logh7.test.runtimeClasspath", sourceSets["test"].runtimeClasspath.asPath) } }
