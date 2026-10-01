import java.net.URI
import java.security.MessageDigest

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(21)
}

// 폰 앱과 같은 파일을 그대로 쓴다: 계산 공식, 화면 해석 규칙, 도감 (두 벌로 관리하지 않기 위해)
val shared = listOf("Calculator.kt", "OcrParser.kt", "PetDb.kt")
sourceSets {
    main {
        kotlin.srcDir(layout.buildDirectory.dir("shared-src"))
        resources.srcDir(layout.buildDirectory.dir("shared-res"))
    }
}
val copyShared by tasks.registering(Copy::class) {
    from("../app/src/main/java/net/g1project/tmcalc") { include(shared) }
    into(layout.buildDirectory.dir("shared-src/net/g1project/tmcalc"))
}
val copyPets by tasks.registering(Copy::class) {
    from("../app/src/main/assets") { include("pets.json") }
    into(layout.buildDirectory.dir("shared-res"))
}
tasks.named("compileKotlin") { dependsOn(copyShared) }
tasks.named("processResources") { dependsOn(copyPets) }

// OCR 모델 (PaddleOCR PP-OCRv5, RapidOCR 배포본). 용량이 커서 레포에 넣지 않고 받아서 SHA-256 으로 확인한다
val models = mapOf(
    "ch_PP-OCRv5_det_mobile.onnx" to Pair(
        "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.2/onnx/PP-OCRv5/det/ch_PP-OCRv5_det_mobile.onnx",
        "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae"),
    "korean_PP-OCRv5_rec_mobile.onnx" to Pair(
        "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.2/onnx/PP-OCRv5/rec/korean_PP-OCRv5_rec_mobile.onnx",
        "cd6e2ea50f6943ca7271eb8c56a877a5a90720b7047fe9c41a2e541a25773c9b"),
)
val modelDir = layout.projectDirectory.dir("models")
val downloadModels by tasks.registering {
    outputs.dir(modelDir)
    doLast {
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        modelDir.asFile.mkdirs()
        for ((name, v) in models) {
            val f = modelDir.file(name).asFile
            if (!f.exists() || sha(f) != v.second) {
                logger.lifecycle("OCR 모델 받는 중: $name")
                URI(v.first).toURL().openStream().use { input -> f.outputStream().use { input.copyTo(it) } }
            }
            check(sha(f) == v.second) { "$name 체크섬이 다릅니다 (파일이 손상됐거나 바뀜)" }
        }
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
    implementation("net.java.dev.jna:jna-platform:5.15.0")
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("net.g1project.tmcalc.desktop.MainKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8")
}

tasks.named<JavaExec>("run") {
    dependsOn(downloadModels)
    systemProperty("tmcalc.models", modelDir.asFile.absolutePath)
}
tasks.test {
    dependsOn(downloadModels)
    systemProperty("tmcalc.models", modelDir.asFile.absolutePath)
}

// ------------------------------------------------------------------ exe 만들기 (Java 런타임 + OCR 모델 포함 폴더, 압축해서 배포)
val appInput = layout.buildDirectory.dir("app-input")
// ONNX Runtime jar 에는 리눅스/맥 파일과 디버그 기호(.pdb)가 들어 있어 크다 → Windows x64 DLL 만 남긴 jar 로 바꾼다
val ortJar = configurations.runtimeClasspath.map { c -> c.files.first { it.name.startsWith("onnxruntime-") } }
val slimOrt by tasks.registering(Jar::class) {
    archiveFileName.set("onnxruntime-win-x64.jar")
    destinationDirectory.set(layout.buildDirectory.dir("slim"))
    from(ortJar.map { zipTree(it) }) {
        exclude("ai/onnxruntime/native/**")
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    }
    from(ortJar.map { zipTree(it) }) { include("ai/onnxruntime/native/win-x64/*.dll") }
}
val prepareApp by tasks.registering(Sync::class) {
    dependsOn(tasks.named("jar"), downloadModels)
    from(tasks.named("jar")); from(slimOrt)
    from(configurations.runtimeClasspath) { exclude("onnxruntime-*.jar") }
    from(modelDir) { into("models") }
    into(appInput)
}
val appVersion = "1.4.1"
fun jpackageArgs(type: String, dest: File) = listOf(
    File(System.getProperty("java.home"), "bin/jpackage").path,
    "--type", type, "--name", "TM-calc", "--app-version", appVersion,
    "--vendor", "gyeongho-you", "--description", "Taming Master 2 growth calculator (PC)",
    "--input", appInput.get().asFile.path, "--main-jar", tasks.named<Jar>("jar").get().archiveFileName.get(),
    "--main-class", "net.g1project.tmcalc.desktop.MainKt",
    "--add-modules", "java.base,java.desktop,java.prefs,java.logging,jdk.unsupported",
    "--java-options", "-Dfile.encoding=UTF-8",
    "--dest", dest.path,
)

/** 압축해서 배포하는 폴더판 (build/dist/TM-calc) */
val packageApp by tasks.registering(Exec::class) {
    dependsOn(prepareApp)
    val out = layout.buildDirectory.dir("dist").get().asFile
    doFirst { out.deleteRecursively() }
    commandLine(jpackageArgs("app-image", out))
}

/**
 * 설치 파일 하나 (build/installer/TM-calc-버전.exe). 만드는 PC 에만 WiX Toolset 3 이 필요하다
 * (기본 위치 C:/dev/tools/wix, -Pwix=경로 로 바꿀 수 있음). 받는 사람은 필요 없음.
 * 사용자 폴더에 설치해서 관리자 권한을 묻지 않고, 같은 upgrade-uuid 라 새 버전을 설치하면 이전 버전을 바꾼다.
 */
val packageInstaller by tasks.registering(Exec::class) {
    dependsOn(prepareApp)
    val out = layout.buildDirectory.dir("installer").get().asFile
    val wix = File((findProperty("wix") as String?) ?: "C:/dev/tools/wix")
    doFirst {
        check(File(wix, "candle.exe").exists()) { "WiX Toolset 3 이 없습니다: $wix (-Pwix=경로 로 지정)" }
        out.deleteRecursively()
    }
    environment("PATH", wix.path + File.pathSeparator + System.getenv("PATH"))
    commandLine(jpackageArgs("exe", out) + listOf(
        "--win-per-user-install", "--win-shortcut", "--win-menu", "--win-menu-group", "TM-calc",
        "--win-upgrade-uuid", "0fea8b85-06f6-4a7b-9429-581130795201",
    ))
}
