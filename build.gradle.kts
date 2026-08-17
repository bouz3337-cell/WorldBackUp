plugins {
    java
}

group = "io.github.yj"

// 1.0.0 이후 백업에 담기는 내용이 바뀌었다(플러그인 자기 config.yml 포함). 같은 버전 번호가
// 서로 다른 동작을 갖지 않도록 올린다. 변경 내역은 CHANGELOG.md.
version = "1.1.0"

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
 * 버전을 한 줄로 출력한다.
 *
 * <p>릴리스 워크플로가 태그 이름과 이 값이 어긋나지 않는지 확인하는 데 쓴다.
 * {@code v1.2.0} 태그를 밀었는데 jar 가 {@code 1.1.0} 으로 나가면, 받는 쪽은 그 사실을
 * 알 방법이 없다.</p>
 */
tasks.register("printVersion") {
    val projectVersion = project.version.toString()
    doLast { println(projectVersion) }
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
