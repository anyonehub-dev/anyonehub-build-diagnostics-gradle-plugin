plugins {
    `java-gradle-plugin`
    kotlin("jvm") version "2.4.10"
    id("com.vanniktech.maven.publish")  version "0.37.0"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(gradleApi())
    implementation(kotlin("stdlib"))
}

gradlePlugin {
    plugins {
        create("projectHealthPlugin") {
            id = "com.anyonehub.diagnostics.health"
            implementationClass = "com.anyonehub.diagnostics.ProjectHealthPlugin"
        }
    }
}

group = "com.anyonehub.diagnostics"
version = "1.0.0"

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

        pom {
            name.set("Anyone-Hub Project Health Plugin")
            description.set("A headless, read-only Gradle diagnostic plugin outputting Markdown reports.")
            inceptionYear.set("2024")
            url.set("https://github.com/anyonehub-dev/anyonehub-build-diagnostics-gradle-plugin")

            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }

            developers {
                developer {
                    id.set("anyone-Hub")
                    name.set("Thomas Alan Slinkard Jr")
                    url.set("https://github.com/anyonehub-dev")
                }
            }

            scm {
                url.set("https://github.com/anyonehub-dev/anyonehub-build-diagnostics-gradle-plugin")
                connection.set("scm:git:git://github.com/anyonehub-dev/anyonehub-build-diagnostics-gradle-plugin.git")
                developerConnection.set("scm:git:ssh://git@github.com/anyonehub-dev/anyonehub-build-diagnostics-gradle-plugin.git")
            }
        }
    }

