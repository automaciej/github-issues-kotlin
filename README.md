# github-issues-kotlin

[![](https://jitpack.io/v/automaciej/github-issues-kotlin.svg)](https://jitpack.io/#automaciej/github-issues-kotlin)

Kotlin library that wraps the [GitHub Issues API](https://docs.github.com/en/rest/issues)
with a local Room cache and exposes it through the shared
[`TaskStore`](https://github.com/automaciej/task-sync-kotlin) contract, built on
[task-sync-kotlin](https://github.com/automaciej/task-sync-kotlin)'s offline-first
sync engine.

This is not a thin, stateless network wrapper: reads and writes go through a
local database that is the source of truth for the UI, reconciled with GitHub
in the background, so the app works fully offline between syncs. GitHub remains
the ultimate source of truth for issue data.

The library never handles GitHub authentication — it takes an
`AccessTokenProvider` (`pl.blizinski.tasksync.model.AccessTokenProvider`, a
single `suspend fun getToken(): String`) supplied by the consuming app, backed
by a Personal Access Token the user pastes in. `GitHubTokenVerifier` is provided
for validating a PAT before it's stored.

## One contract, four sources

`github-issues-kotlin`, `google-tasks-kotlin`, `microsoft-todo-kotlin` and
`todoist-kotlin` are separate, independently-versioned libraries that **all
expose the same `pl.blizinski.tasksync.store.TaskStore` interface over the same
`pl.blizinski.tasksync.model.Task` / `TaskList` types**. A consuming app can hold
several of them side by side and treat them uniformly, branching only on each
one's declared `StoreCapabilities` (`GitHubIssues.capabilities`) — e.g. GitHub
has no due date, no priority, and a "list" is a pre-existing repository the app
neither creates nor deletes.

## API

```kotlin
// Android
val store: TaskStore = gitHubIssuesStore(
    context,
    tokenProvider,                       // AccessTokenProvider
    StoreConfig(dbName = "github_issues_store_$accountId"),
)
// wasmJs
val store: TaskStore = gitHubIssuesWasmStore(tokenProvider, StoreConfig(dbName = "github_issues_store"))
```

`TaskStore` gives you `Flow`s of task lists (repositories) and tasks (issues) per
list, a `Flow<SyncStatus>`, optimistic `createTask`/`updateTask`/`completeTask`/
`uncompleteTask`/`deleteTask` (delete closes the issue — GitHub's REST API has no
delete endpoint), and `forceSync()`/`fullSync()`. `createList`/`updateList`/
`deleteList` throw — `GitHubIssues.capabilities.supportsListCreation` is `false`.

Op-merging, tombstone detection, per-account polling isolation, and
`SyncErrorKind` classification are inherited from `task-sync-kotlin`.

## Targets

`androidTarget` (Room + `androidx.work`) and a `wasmJs` target
(`gitHubIssuesWasmStore`, IndexedDB, sync-on-demand). wasmJs is excluded from
JitPack builds (see `jitpack.yml`).

## Usage

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories { maven { url = uri("https://jitpack.io") } }
}
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.github.automaciej:github-issues-kotlin:v0.2.0")
}
```

Implement `AccessTokenProvider` against your app's PAT storage, call
`gitHubIssuesStore(...)`, and consume the returned `TaskStore`.

## Build

```
./build.sh build
```
