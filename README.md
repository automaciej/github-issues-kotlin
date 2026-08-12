# github-issues-store

[![](https://jitpack.io/v/automaciej/github-issues-kotlin.svg)](https://jitpack.io/#automaciej/github-issues-kotlin)

Android library that wraps the [GitHub Issues API](https://docs.github.com/en/rest/issues)
with a local Room cache and exposes a reactive `GitHubIssuesStoreApi`, built
on top of [task-sync-kotlin](https://github.com/automaciej/task-sync-kotlin)'s
shared offline-first sync engine.

This is not a thin, stateless network wrapper: reads and writes go through a
local Room database that is the actual source of truth for the UI, kept in
sync with GitHub in the background. GitHub itself remains the ultimate
source of truth for issue data; this library's cache is what lets the app
work fully offline in between syncs.

This library never handles GitHub authentication itself — it takes a
`GitHubAccessTokenProvider` supplied by the consuming app (backed by a
Personal Access Token the user pastes in), which owns the actual credential
storage. This keeps the library free of any client ID or other
app-specific credential.

## Features

- **`GitHubIssuesStoreApi`**: reactive `Flow`s of task lists (repositories)
  and tasks (issues) per list, plus a `Flow<SyncStatus>` for surfacing sync
  errors/progress in the UI.
- **Adaptive background polling and pending-op merging** inherited from
  `task-sync-kotlin`: op-merging, tombstone detection, per-account polling
  isolation via `AdaptivePoller`, and structured `SyncErrorKind`
  classification specific to GitHub's auth/rate-limit errors.
- **`forceSync()` / `fullSync()`**: run a sync cycle synchronously on demand,
  with `fullSync()` re-pulling every list from scratch to repair local state
  that drifted in a way incremental sync can't catch.
- **wasmJs proof-of-concept target** alongside the primary Android target —
  reuses `task-sync-kotlin`'s `SyncEngine`/`PendingOpsProcessor` (commonMain)
  with an in-memory store instead of Room, no `AdaptivePoller`/WorkManager.

## What it is *not*

- **Not a general-purpose task-list abstraction.** `Task`/`TaskList` here
  are shaped around GitHub Issues (title, body, state). It's not meant to
  be swapped for another source's schema — that's what `google-tasks-kotlin`/
  `microsoft-todo-kotlin`/`todoist-kotlin` are, as separate,
  independently-versioned libraries sharing the same underlying engine.

## Usage

Add the JitPack repository:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the dependency:

```kotlin
dependencies {
    implementation("com.github.automaciej:github-issues-kotlin:v0.1.0")
}
```

Implement `GitHubAccessTokenProvider` against your app's own PAT storage,
construct a `GitHubIssuesStore` with it, then consume it through
`GitHubIssuesStoreApi`.

## Build

```
./build.sh build
```
