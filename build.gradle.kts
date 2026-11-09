plugins {
    java
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.244-stable")
    compileOnly("com.zaxxer:HikariCP:7.0.2")
    // 供 MikuProtectLoader 引用 MavenLibraryResolver 的 aether 类型
    compileOnly("org.apache.maven.resolver:maven-resolver-api:1.9.22")
    // 只取 WorldEdit API 类；关掉传递依赖，避免它的 strict 版本约束与 paper-api 冲突
    compileOnly("com.sk89q.worldedit:worldedit-core:7.3.12") {
        isTransitive = false
    }
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.12") {
        isTransitive = false
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
    // ActivityRecord 的方法签名引用 Bukkit 类型，测试运行期也需要 paper-api
    testImplementation("io.papermc.paper:paper-api:26.3.build.244-stable")
    testImplementation("org.duckdb:duckdb_jdbc:1.4.5.0")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    filesMatching("paper-plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveFileName = "MikuProtect-${project.version}.jar"
}
