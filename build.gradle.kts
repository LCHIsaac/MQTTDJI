import java.util.zip.ZipFile

plugins {
    id("com.android.application") version "8.3.2" apply false
    id("com.android.library") version "8.3.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.23" apply false
    id("org.jetbrains.kotlin.jvm") version "1.9.23" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.23" apply false
}

val sdkManagerDexMarker = "dji/v5/manager/SDKManager"

fun containsAsciiSubsequence(haystack: ByteArray, needle: ByteArray): Boolean {
    if (needle.isEmpty()) return true
    if (haystack.size < needle.size) return false
    for (i in 0..haystack.size - needle.size) {
        var match = true
        for (j in needle.indices) {
            if (haystack[i + j] != needle[j]) {
                match = false
                break
            }
        }
        if (match) return true
    }
    return false
}

fun Project.registerVerifyDjiSdkDexTask(apkTaskPath: String, apkRelativePath: String) {
    val verifyTask = tasks.register("verifyDjiSdkDex") {
        group = "verification"
        description = "Fails if debug APK dex does not contain dji.v5.manager.SDKManager"
        dependsOn(apkTaskPath)
        doLast {
            val apk = layout.projectDirectory.file(apkRelativePath).asFile
            if (!apk.exists()) {
                throw GradleException("APK not found: ${apk.absolutePath}")
            }
            val needle = sdkManagerDexMarker.encodeToByteArray()
            val found = ZipFile(apk).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".dex") }
                    .any { entry ->
                        zip.getInputStream(entry).use { input ->
                            val bytes = input.readBytes()
                            containsAsciiSubsequence(bytes, needle)
                        }
                    }
            }
            if (!found) {
                throw GradleException(
                    "Missing $sdkManagerDexMarker in ${apk.name} — add implementation com.dji:dji-sdk-v5-aircraft to the app module"
                )
            }
            logger.lifecycle("Verified $sdkManagerDexMarker present in ${apk.name}")
        }
    }
    afterEvaluate {
        tasks.findByName("check")?.dependsOn(verifyTask)
    }
}

subprojects {
    when (name) {
        "app-phone" -> registerVerifyDjiSdkDexTask(
            ":app-phone:assembleDebug",
            "build/outputs/apk/debug/app-phone-debug.apk"
        )
        "app-rcpro" -> registerVerifyDjiSdkDexTask(
            ":app-rcpro:assembleDebug",
            "build/outputs/apk/debug/app-rcpro-debug.apk"
        )
    }
}

tasks.register("verifyDjiSdkInApks") {
    group = "verification"
    description = "Verify SDKManager is packaged in both app-phone and app-rcpro debug APKs"
    dependsOn(
        ":app-phone:verifyDjiSdkDex",
        ":app-rcpro:verifyDjiSdkDex"
    )
}
