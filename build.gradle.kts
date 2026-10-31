plugins {
    id("java-library")
}

// 离线编译：javac 对着 mojmap 编译产物 + SBW/TLM 生产 jar（与 sbw-radar 等附属同一工具链）
// 依赖：Superb Warfare 0.8.9.1（GPL-3.0，运行时链接）+ Touhou Little Maid 1.5.3（MIT API）
// 因此本 mod 以 GPL-3.0 分发。

version = project.property("mod_version") as String
group = "com.zzy.maidvehicle"

base { archivesName.set(project.property("mod_id") as String) }

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(fileTree("libs") { include("*.jar") })
}

tasks.withType<JavaCompile> { options.encoding = "UTF-8" }

tasks.jar {
    manifest { attributes("Implementation-Version" to project.version) }
}
