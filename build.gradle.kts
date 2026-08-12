import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinSerialization)
    `maven-publish`
}

group = "pl.blizinski"
version = "0.1.0"

kotlin {
    android {
        namespace = "pl.blizinski.githubissuesstore"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }

        withHostTestBuilder {}.configure {
            isReturnDefaultValues = true
        }
    }

    // wasmJs proof-of-concept target — see TaskCompass's
    // Docs/designs/2026-07-29-web-wasmjs-github-issues-poc.md and
    // Docs/designs/2026-07-30-web-wasmjs-google-tasks-poc.md (Stage G). Additive only: does
    // not touch the android {} block above. Reuses task-sync-kotlin's SyncEngine/
    // PendingOpsProcessor directly (now commonMain) with an InMemoryLocalStore instead of
    // Room, and no AdaptivePoller/WorkManager (see GitHubIssuesStoreWasm in wasmJsMain).
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.room.runtime)
            implementation(libs.room.ktx)
            implementation(libs.work.runtime.ktx)
            implementation(libs.okhttp)
            // Resolved via JitPack normally; substituted for the local checkout when one exists
            // as a sibling directory — see the root settings.gradle.kts.
            implementation("com.github.automaciej:task-sync-kotlin:v0.1.1")
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }
        val wasmJsMain by getting {
            dependencies {
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.js)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                // Resolved via JitPack normally; substituted for the local checkout when one
                // exists as a sibling directory — see the root settings.gradle.kts.
                implementation("com.github.automaciej:task-sync-kotlin:v0.1.1")
            }
        }
    }
}

// KSP generates Room's implementation code — not for any entity defined in this module (it
// defines none of its own), but for TaskSyncDatabase's Room.databaseBuilder(...) call site to
// resolve correctly, matching the same inclusion in task-sync-kotlin's and
// microsoft-todo-kotlin's own build.gradle.kts.
dependencies {
    add("kspAndroid", libs.room.compiler)
}
