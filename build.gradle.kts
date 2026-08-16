plugins {
    java
}

group = "io.github.yj"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Minecraft 26.2 (Paper). 서버가 제공하므로 compileOnly.
    compileOnly("io.papermc.paper:paper-api:26.2.build.112-stable")

    testImplementation("io.papermc.paper:paper-api:26.2.build.112-stable")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}

java {
    // 26.1 부터 마인크래프트는 Java 25 를 요구한다.
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:-serial")
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") { expand(props) }
}

tasks.jar {
    archiveBaseName.set("WorldBackUp")
    archiveClassifier.set("")
}

/**
 * 빌드된 jar 를 서버 plugins 폴더로 복사한다.
 *   gradlew deployPlugin -PserverDir="D:/minecraft/server"
 */
tasks.register<Copy>("deployPlugin") {
    dependsOn(tasks.jar)
    val serverDir = providers.gradleProperty("serverDir")
    onlyIf { serverDir.isPresent }
    from(tasks.jar)
    into(serverDir.map { "$it/plugins" })
    doLast { logger.lifecycle("플러그인을 ${serverDir.get()}/plugins 로 복사했습니다.") }
}
