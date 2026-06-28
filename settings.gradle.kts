pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin") {
            name = "Aliyun Gradle Plugin"
        }
        maven("https://maven.aliyun.com/repository/public") {
            name = "Aliyun Public"
        }
        maven("https://maven.fabricmc.net/") {
            name = "Fabric"
        }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public") {
            name = "Aliyun Public"
        }
        maven("https://maven.fabricmc.net/") {
            name = "Fabric"
        }
        maven("https://libraries.minecraft.net/") {
            name = "Mojang"
            metadataSources {
                mavenPom()
                artifact()
            }
        }
        mavenCentral()
    }
}
