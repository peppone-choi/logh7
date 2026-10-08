plugins { kotlin("jvm") }
dependencies {
implementation(project(":protocol"))
implementation(project(":engine"))
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
implementation("io.netty:netty-all:4.2.18.Final")
}
