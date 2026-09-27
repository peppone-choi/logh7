plugins { kotlin("jvm") }
dependencies {
implementation(project(":protocol"))
implementation(project(":engine"))
implementation("io.netty:netty-all:4.2.18.Final")
}
