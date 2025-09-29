pluginManagement {
    repositories {
        // Thứ tự ưu tiên: Google trước để ML Kit/AndroidX resolve chuẩn
        google()
        mavenCentral()
        gradlePluginPortal()
        // Dự phòng cho các lib phát hành qua GitHub (không bắt buộc, nhưng nên có)
        maven { url = uri("https://jitpack.io") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Dự phòng (một số lib có thể cần)
        maven { url = uri("https://jitpack.io") }

        maven { url = uri("https://alphacephei.com/maven") }
    }
}

rootProject.name = "SimpleDictionary"
include(":app")
