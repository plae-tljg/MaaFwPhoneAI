import java.util.Properties

plugins {
    id("maafw.android.application")
    id("maafw.android.compose")
    id("maafw.pi.assets")
    id("maafw.agent.runtime")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.baselineprofile)
}

// Build-time DeepSeek key for debug builds. Precedence:
// env DEEPSEEK_API_KEY > -Pdeepseek.apiKey=... > android/.env > proto/.env
val brainEnv = Properties().apply {
    listOf(rootProject.file(".env"), rootProject.file("proto/.env"))
        .firstOrNull { it.isFile }
        ?.inputStream()?.use { load(it) }
}
fun brainValue(key: String): String =
    System.getenv(key) ?: (project.findProperty(key) as? String) ?: brainEnv.getProperty(key).orEmpty()

val deepseekKey = brainValue("DEEPSEEK_API_KEY")
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
val deepseekModel = brainValue("DEEPSEEK_MODEL").ifBlank { "deepseek-v4-flash" }
val deepseekBase = brainValue("DEEPSEEK_BASE").ifBlank { "https://api.deepseek.com" }

android {
    namespace = "com.aliothmoon.maafw"

    packaging {
        jniLibs {
            // MaaFramework release .so are already stripped. The host NDK's
            // llvm-strip corrupts some of them (libfastdeploy_ppocr.so ends up
            // with a broken dynamic table: "empty/missing DT_HASH"), so keep
            // the prebuilt native libraries untouched and strip only our own.
            keepDebugSymbols += listOf(
                "**/libMaa*.so",
                "**/libfastdeploy_ppocr.so",
                "**/libopencv_world4.so",
                "**/libonnxruntime.so",
                "**/libc++_shared.so",
                "**/libjnidispatch.so"
            )
        }
    }
    // Host SDK has 27.1; override the upstream-preferred 28.2 for this machine.
    ndkVersion = "27.1.12297006"

    defaultConfig {
        // Never ship a build-time key in release. Debug builds get the local
        // .env value below; release always embeds an empty string and asks the
        // user for the key in Settings.
        buildConfigField("String", "DEEPSEEK_API_KEY", "\"\"")
        buildConfigField("String", "DEEPSEEK_MODEL", "\"$deepseekModel\"")
        buildConfigField("String", "DEEPSEEK_BASE", "\"$deepseekBase\"")
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // Local development convenience only; this is why debug APKs must
            // never be treated as distributable artifacts.
            buildConfigField("String", "DEEPSEEK_API_KEY", "\"$deepseekKey\"")
        }
        getByName("release") {
            buildConfigField("String", "DEEPSEEK_API_KEY", "\"\"")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/native/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = true
        aidl = true
    }

    androidResources {
        noCompress += "zip"
    }
}

baselineProfile {
    // 落进 src/main 而不是 src/release：测量用的 benchmark 是另一个 build type，
    // 吃不到 src/release 的源集，不合并的话量出来会是「profile 毫无效果」
    mergeIntoMain = true
}

dependencies {
    compileOnly(project(":hidden-api"))

    implementation(project(":annotation-api"))
    ksp(project(":ksp-processor"))
    implementation(project(":semi-icons"))
    // Baseline Profile 的生产者；采集用例在那个模块里
    baselineProfile(project(":macrobenchmark"))

    // MIUI 上系统权限页的跳转差异大，自己拼 Intent 覆盖不全
    implementation(libs.xx.permissions)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.libsu)
    // aar 里带 libjnidispatch.so，用 jar 会在设备上找不到 native 分发库
    implementation(libs.jna) { artifact { type = "aar" } }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.window)
    // 解包与项目加载各圈一段，Perfetto / macrobenchmark 里才归得了因
    implementation(libs.androidx.tracing.ktx)
    // Baseline Profile 在 API 33 以下靠它在启动时装入
    implementation(libs.androidx.profileinstaller)

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    implementation(libs.timber)
    implementation(libs.reorderable)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.html)
    implementation(libs.markwon.image)
    implementation(libs.markwon.linkify)
    implementation(libs.markwon.strikethrough)
    implementation(libs.okhttp)
    implementation(libs.sentry.android.core)
    // Android 没有自带的 mail 实现，三个一起才跑得起 Transport.send
    implementation(libs.angus.mail)
    implementation(libs.angus.activation)
    implementation(libs.jakarta.activation.api)
    // 前台模式控制层：拖拽/吸边/多屏/返回键拦截都在库里，自己写这几样是纯坑区
    implementation(libs.floatingx)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    // Local JVM tests need a real org.json implementation; android.jar only
    // ships stubs that throw "not mocked".
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    // Perfetto 里把 slice 命名到 composable；只进 debug，release 不带
    debugImplementation(libs.androidx.compose.runtime.tracing)
    debugImplementation(libs.androidx.tracing.perfetto.binary)
}
