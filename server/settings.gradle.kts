pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
rootProject.name = "logh7-server"
include("protocol", "engine", "gateway", "ops-api", "persistence", "app")
