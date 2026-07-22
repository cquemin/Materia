# wgpu4k namespace bridge — TEMPORARY

`wgpu4k-toolkit-android-debug:0.1.1` and `wgpu4k-android-debug:0.1.1` both declare the manifest
namespace `io.ygdrasil.wgpu4k`; `webgpu-ktypes-descriptors-android-debug:0.0.7` and
`webgpu-ktypes-android-debug:0.0.7` both declare `io.ygdrasil.webgpu.ktypes`. AGP requires every
Android library in a graph to declare a unique namespace, so any external consumer of the published
AARs fails `processDebugMainManifest`. Upstream's own Android example consumes both modules as
Gradle *project* dependencies, which registers one namespace — which is why upstream cannot see it.

This project republishes the two colliding artifacts under `ai.assistant.thirdparty` with `-ns1`
version suffixes and corrected namespaces. It changes two string literals per artifact and nothing
else. It is safe because both AARs ship an empty `R.txt`, no `res/` entries and no `R` classes — the
build asserts all three and fails if that ever stops being true.

## Run

    ./gradlew -p thirdparty/wgpu4k-namespace-fix repackageAll
    ./gradlew -p thirdparty/wgpu4k-namespace-fix publishAllPublicationsToLocalBridgeRepoRepository
    ./gradlew -p thirdparty/wgpu4k-namespace-fix publishAllPublicationsToGitHubPackagesRepository \
        -Pgpr.user=<user> -Pgpr.key=<token>

GitHub Packages versions are **immutable**. Verify against the local repo first.

## Delete this when

`wgpu4k/wgpu4k` and `wgpu4k/webgpu-ktypes` release with unique Android namespaces. Removal path is
in `ai-assistant/docs/superpowers/plans/2026-07-20-wgpu4k-namespace-bridge.md`, Task 9.
