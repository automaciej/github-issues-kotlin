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

// Publishing more than one target breaks JitPack's Gradle module metadata for downstream KMP
// consumers (see task-sync-kotlin's build.gradle.kts for the full explanation) — TaskCompass
// only ever consumes this library's android target via JitPack, so wasmJs is skipped for
// JitPack builds (set via `-PjitpackBuild=true` in jitpack.yml); local/POC development on the
// target is unaffected.
val isJitpackBuild = project.hasProperty("jitpackBuild")

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
    // Skipped on JitPack — see isJitpackBuild above.
    if (!isJitpackBuild) {
        @OptIn(ExperimentalWasmDsl::class)
        wasmJs {
            browser()
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
            // Resolved via JitPack normally; substituted for the local checkout when one exists
            // as a sibling directory — see the root settings.gradle.kts.
            implementation("com.github.automaciej:task-sync-kotlin:v0.4.1")
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.room.runtime)
            implementation(libs.room.ktx)
            implementation(libs.work.runtime.ktx)
            implementation(libs.okhttp)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }
        if (!isJitpackBuild) {
            val wasmJsMain by getting {
                dependencies {
                    implementation(libs.kotlinx.serialization.json)
                    implementation(libs.ktor.client.core)
                    implementation(libs.ktor.client.js)
                    implementation(libs.ktor.client.content.negotiation)
                    implementation(libs.ktor.serialization.kotlinx.json)
                    implementation("com.github.automaciej:task-sync-kotlin:v0.4.1")
                }
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

// Don't publish Gradle Module Metadata — JitPack serves the synthetic flat coordinate
// (com.github.automaciej:github-issues-kotlin) as POM + stub jar, and a stray .module file makes its
// flat-coordinate synthesis emit the POM without the stub jar it references, breaking downstream
// resolution ("Could not find github-issues-kotlin-<tag>.jar"). Nothing consuming this library needs the .module.
tasks.withType<GenerateModuleMetadata>().configureEach { enabled = false }
