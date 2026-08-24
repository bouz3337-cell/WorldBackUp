plugins {
    java
}

group = "io.github.yj"

// 1.0.0 이후 백업에 담기는 내용이 바뀌었다(플러그인 자기 config.yml 포함). 같은 버전 번호가
// 서로 다른 동작을 갖지 않도록 올린다. 변경 내역은 CHANGELOG.md.
version = "1.3.0"

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
 *
 * <p>버전이 파일명에 들어가므로 복사만 하면 옛 jar 가 그대로 남는다. 둘 다 있으면 서버는
 * "Ambiguous plugin name" 을 찍고 <b>한쪽을 무시한다.</b> 죽지는 않아서 알아채기 어렵고,
 * 어느 쪽이 살아남는지에 기대는 것도 좋지 않다. 그래서 복사 전에 옛 jar 를 치운다.</p>
 */
tasks.register<Copy>("deployPlugin") {
    dependsOn(tasks.jar)
    val serverDir = providers.gradleProperty("serverDir")
    onlyIf { serverDir.isPresent }
    doFirst {
        val stale = File(serverDir.get(), "plugins")
                .listFiles { file -> file.name.matches(Regex("WorldBackUp-.+[.]jar")) }
        stale?.forEach { old ->
            if (old.delete()) logger.lifecycle("옛 jar 를 치웠습니다: ${old.name}")
            // 윈도우는 켜져 있는 서버의 jar 를 잠근다. 그 상태로 복사하면 두 개가 남는다.
            else logger.warn("옛 jar 를 지우지 못했습니다 (서버가 켜져 있나요?): ${old.name}")
        }
    }
    from(tasks.jar)
    into(serverDir.map { "$it/plugins" })
    doLast { logger.lifecycle("플러그인을 ${serverDir.get()}/plugins 로 복사했습니다.") }
}
