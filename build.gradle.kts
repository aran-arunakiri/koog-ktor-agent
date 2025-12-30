plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    `maven-publish`
}

group = "tech.abstracty"
version = "0.2.3"

repositories {
    mavenCentral()
}

dependencies {
    // Koog
    api("ai.koog:koog-ktor:0.5.3")

    // Ktor Server
    api("io.ktor:ktor-server-core:3.2.3")
    implementation("io.ktor:ktor-client-core:3.2.3")
    implementation("io.ktor:ktor-client-cio:3.2.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.2.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.2.3")

    // Serialization
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // Qdrant
    implementation("io.qdrant:client:1.15.0")
    implementation("com.google.protobuf:protobuf-java:3.25.5")

    // Web search
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("org.apache.pdfbox:pdfbox:3.0.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.7.3")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("Koog Ktor Agent")
                description.set("Reusable library for building Koog-based AI agents with Ktor, supporting multiple frontend protocols (Assistant-UI, OpenAI SSE)")
                url.set("https://github.com/aran-arunakiri/koog-ktor-agent")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                developers {
                    developer {
                        id.set("aran-arunakiri")
                        name.set("Aran Arunakiri")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/aran-arunakiri/koog-ktor-agent.git")
                    developerConnection.set("scm:git:ssh://github.com/aran-arunakiri/koog-ktor-agent.git")
                    url.set("https://github.com/aran-arunakiri/koog-ktor-agent")
                }
            }
        }
    }

    repositories {
        // Local repository for testing
        maven {
            name = "local"
            url = uri(layout.buildDirectory.dir("repo"))
        }

        // GitHub Packages
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/aran-arunakiri/koog-ktor-agent")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_USERNAME")
                password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
