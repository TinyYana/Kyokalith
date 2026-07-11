plugins {
    kotlin("jvm") version libs.versions.kotlin.get()
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly(libs.paper.api)
    // stdlib 不 shade:runtime 由 plugin.yml 的 libraries(Paper library loader)提供,版本要與此一致
    compileOnly(libs.kotlin.stdlib)
    testImplementation(libs.paper.api)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    jar {
        archiveClassifier.set("thin")
    }

    shadowJar {
        archiveClassifier.set("")
    }

    runServer {
        minecraftVersion(libs.versions.minecraft.get())
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    test {
        useJUnitPlatform()

        // DocsCoverageTest 直接從磁碟讀這幾個檔,Gradle 預設不知道它們是 :test 的輸入。
        // 不宣告的話,「只改了文件或 plugin.yml」會讓 :test 判定 UP-TO-DATE 而整個跳過
        // (CI 上還會被 build cache 直接 restore),文件檢查就等於不存在。
        inputs.file("src/main/resources/plugin.yml")
        inputs.file("README.md")
        inputs.dir("docs")
    }

    processResources {
        val props = mapOf("version" to version, "description" to project.description)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
