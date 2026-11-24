// El espejo de Maven Central de Google va primero: Maven Central a veces contesta «429, demasiadas peticiones».
val espejoCentral = "https://maven-central.storage-download.googleapis.com/maven2/"
pluginManagement { repositories { google(); maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }; mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); maven { url = uri(espejoCentral) }; mavenCentral() } }
rootProject.name = "Atlas"
include(":app")
