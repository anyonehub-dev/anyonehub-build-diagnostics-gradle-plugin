plugins {
    id("java")
}

val declaredDeps = objects.listProperty(String::class.java)

configurations.configureEach { cfg ->
    cfg.dependencies.configureEach { dep ->
        if (dep is ExternalDependency && dep.version != null) {
            declaredDeps.add("${dep.group.orEmpty()}:${dep.name}:${dep.version}")
        }
    }
}

dependencies {
    implementation("com.google.guava:guava:31.0-jre")
    testImplementation("junit:junit:4.13.2")
}

tasks.register("printDeps") {
    doLast {
        println("Declared deps: " + declaredDeps.get())
    }
}
