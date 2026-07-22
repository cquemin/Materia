// TODO(sp1-wgpu4k-ns-bridge): delete this whole directory when wgpu4k/wgpu4k and
// wgpu4k/webgpu-ktypes publish unique Android namespaces upstream.
//
// Standalone on purpose: this must NOT participate in Materia's own build or its
// `publishAll` task graph. Invoke with:
//     ./gradlew -p thirdparty/wgpu4k-namespace-fix <task>
rootProject.name = "wgpu4k-namespace-fix"

dependencyResolutionManagement {
    repositories { mavenCentral() }
}
