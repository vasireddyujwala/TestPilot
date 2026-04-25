plugins {
    java
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.executionagent"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

val dockerJavaVersion = "3.3.6"
val jacksonVersion = "2.16.1"
val okhttpVersion = "4.12.0"
val picocliVersion = "4.7.5"
val logbackVersion = "1.4.14"

dependencies {
    // Docker Java SDK
    implementation("com.github.docker-java:docker-java-core:$dockerJavaVersion")
    implementation("com.github.docker-java:docker-java-transport-zerodep:$dockerJavaVersion")

    // Jackson JSON
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.core:jackson-annotations:$jacksonVersion")

    // OkHttp for LLM API calls
    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    // CLI
    implementation("info.picocli:picocli:$picocliVersion")
    annotationProcessor("info.picocli:picocli-codegen:$picocliVersion")

    // HTML parsing
    implementation("org.jsoup:jsoup:1.17.2")

    // YAML
    implementation("org.yaml:snakeyaml:2.2")

    // Commons
    implementation("commons-io:commons-io:2.15.1")
    implementation("org.apache.commons:commons-lang3:3.14.0")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "com.executionagent.Main"
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-Aproject=${project.group}/${project.name}"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName = "execution-agent"
    archiveClassifier = ""
    archiveVersion = project.version.toString()
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "com.executionagent.Main"
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
