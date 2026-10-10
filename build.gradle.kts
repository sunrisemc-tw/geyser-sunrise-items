plugins {
    java
}

val geyserApiVersion = "2.11.3"

repositories {
    maven("https://repo.opencollab.dev/main/")
    mavenCentral()
}

dependencies {
    // Geyser API（compileOnly：執行時由 Geyser 本體提供）
    compileOnly("org.geysermc.geyser:api:$geyserApiVersion-SNAPSHOT")
    // JSON 解析用 Gson（Geyser 本體已帶，compileOnly 即可）
    compileOnly("com.google.code.gson:gson:2.11.0")
}

java {
    targetCompatibility = JavaVersion.VERSION_21
    sourceCompatibility = JavaVersion.VERSION_21
}

tasks {
    processResources {
        filesMatching("extension.yml") {
            expand(
                "id" to "sunrise-items",
                "name" to "SunriseItems",
                "api" to geyserApiVersion,
                "version" to (project.version.toString()),
                "author" to "duckducktw"
            )
        }
    }
}
