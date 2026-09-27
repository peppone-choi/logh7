plugins { kotlin("jvm") }
dependencies {
implementation(project(":engine"))
implementation("io.ktor:ktor-server-core-jvm:3.6.0")
implementation("io.ktor:ktor-server-netty-jvm:3.6.0")
}
