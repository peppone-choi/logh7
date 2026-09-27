plugins { kotlin("jvm"); application }
dependencies { implementation(project(":gateway")); implementation(project(":engine")); implementation(project(":ops-api")); implementation(project(":persistence")); implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0") }
application { mainClass.set("org.logh7.app.MainKt") }
