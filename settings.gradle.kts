pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "TamingCalcOverlay"
include(":app")
// PC(Windows)용: 앱플레이어 창을 캡처해 같은 계산을 한다. 계산/해석 코드는 app 과 같은 파일을 쓴다
include(":desktop")
