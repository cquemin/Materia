import java.io.File
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// TODO(sp1-wgpu4k-ns-bridge): delete when upstream publishes unique Android namespaces.
// Upstream bugs: wgpu4k/wgpu4k#<n>, wgpu4k/webgpu-ktypes#<n>   (filled in by Task 8)
//
// Republishes ONLY the two Android AARs whose manifest namespace collides with one of
// their own transitive dependencies, under group `ai.assistant.thirdparty` with a `-ns1`
// version suffix. Two literal strings change per artifact:
//   * AndroidManifest.xml   package="<old>"  ->  package="<new>"
//   * the POM's groupId + version
// POM *dependencies* keep their original io.ygdrasil coordinates — consumer-side
// dependencySubstitution is transitive and catches the nested edge without the POM
// needing any knowledge of this bridge.

plugins {
    `maven-publish`
}

group = "ai.assistant.thirdparty"

/** One artifact to bridge. */
data class Bridged(
    val artifactId: String,
    val upstreamVersion: String,
    val oldNamespace: String,
    val newNamespace: String,
) {
    val bridgedVersion: String get() = "$upstreamVersion-ns1"
}

val bridged = listOf(
    Bridged(
        artifactId = "wgpu4k-toolkit-android-debug",
        upstreamVersion = "0.1.1",
        oldNamespace = "io.ygdrasil.wgpu4k",
        newNamespace = "io.ygdrasil.wgpu4k.toolkit",
    ),
    Bridged(
        artifactId = "webgpu-ktypes-descriptors-android-debug",
        upstreamVersion = "0.0.7",
        oldNamespace = "io.ygdrasil.webgpu.ktypes",
        newNamespace = "io.ygdrasil.webgpu.ktypes.descriptors",
    ),
)

val mavenCentralBase = "https://repo1.maven.org/maven2/io/ygdrasil"
val workDir: File = layout.buildDirectory.dir("bridge").get().asFile

fun download(url: String, target: File) {
    target.parentFile.mkdirs()
    logger.lifecycle("downloading $url")
    URI(url).toURL().openStream().use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    }
    require(target.length() > 0) { "downloaded 0 bytes from $url" }
}

/**
 * Hard precondition, not a nicety. The rename is only safe because these AARs carry no
 * resources: an empty R.txt, no res/ entries and no generated R classes mean the namespace
 * is manifest identity and nothing compiled refers to it. If a future upstream version ever
 * starts shipping resources, this build must fail rather than silently produce an artifact
 * whose resource package no longer matches its compiled references.
 */
fun assertNamespaceIsInert(aar: File) {
    var sawRTxt = false
    ZipInputStream(aar.inputStream().buffered()).use { zin ->
        var entry = zin.nextEntry
        while (entry != null) {
            val name = entry.name
            when {
                name == "R.txt" -> {
                    sawRTxt = true
                    val bytes = zin.readBytes()
                    require(bytes.isEmpty()) {
                        "${aar.name}: R.txt is ${bytes.size} bytes — this AAR declares resources, " +
                            "so its namespace is NOT inert and must not be renamed."
                    }
                }
                name.startsWith("res/") && !entry.isDirectory ->
                    error("${aar.name}: contains resource entry '$name' — namespace is not inert.")
                name.endsWith("/R.class") || name.contains("/R$") ->
                    error("${aar.name}: contains generated R class '$name' — namespace is not inert.")
            }
            entry = zin.nextEntry
        }
    }
    require(sawRTxt) { "${aar.name}: no R.txt entry — unexpected AAR layout, refusing to repackage." }
}

/** Copies the AAR entry-by-entry, rewriting exactly the manifest package attribute. */
fun repackage(source: File, target: File, oldNamespace: String, newNamespace: String) {
    val oldAttr = "package=\"$oldNamespace\""
    val newAttr = "package=\"$newNamespace\""
    var rewrote = false
    target.parentFile.mkdirs()
    ZipInputStream(source.inputStream().buffered()).use { zin ->
        ZipOutputStream(target.outputStream().buffered()).use { zout ->
            var entry = zin.nextEntry
            while (entry != null) {
                val bytes = zin.readBytes()
                val outBytes = if (entry.name == "AndroidManifest.xml") {
                    val text = bytes.toString(Charsets.UTF_8)
                    require(text.contains(oldAttr)) {
                        "${source.name}: manifest does not contain $oldAttr — refusing to repackage. " +
                            "Manifest was:\n$text"
                    }
                    rewrote = true
                    text.replace(oldAttr, newAttr).toByteArray(Charsets.UTF_8)
                } else {
                    bytes
                }
                // A fresh ZipEntry (DEFLATED by default) is required: reusing the source
                // entry carries its original size/CRC, which no longer match the manifest.
                zout.putNextEntry(ZipEntry(entry.name))
                zout.write(outBytes)
                zout.closeEntry()
                entry = zin.nextEntry
            }
        }
    }
    check(rewrote) { "${source.name}: no AndroidManifest.xml entry found." }
}

/** Grafts the upstream POM's <dependencies> block into the generated POM, verbatim. */
fun upstreamDependenciesNode(pom: File): org.w3c.dom.Node? {
    val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = false }
        .newDocumentBuilder()
        .parse(pom)
    val nodes = doc.documentElement.getElementsByTagName("dependencies")
    for (i in 0 until nodes.length) {
        // Only the top-level <dependencies>, never <dependencyManagement><dependencies>.
        if (nodes.item(i).parentNode === doc.documentElement) return nodes.item(i)
    }
    return null
}

val repackageAll by tasks.registering {
    group = "bridge"
    description = "Download each upstream AAR+POM and rewrite its manifest namespace."
    outputs.dir(workDir)
    doLast {
        bridged.forEach { b ->
            val base = "$mavenCentralBase/${b.artifactId}/${b.upstreamVersion}"
            val stem = "${b.artifactId}-${b.upstreamVersion}"
            val srcAar = File(workDir, "upstream/$stem.aar")
            val srcPom = File(workDir, "upstream/$stem.pom")
            download("$base/$stem.aar", srcAar)
            download("$base/$stem.pom", srcPom)

            assertNamespaceIsInert(srcAar)

            val outAar = File(workDir, "bridged/${b.artifactId}-${b.bridgedVersion}.aar")
            repackage(srcAar, outAar, b.oldNamespace, b.newNamespace)
            logger.lifecycle("repackaged ${b.artifactId}: ${b.oldNamespace} -> ${b.newNamespace}")
        }
    }
}

publishing {
    publications {
        bridged.forEach { b ->
            register<MavenPublication>(b.artifactId.replace("-", "_")) {
                groupId = "ai.assistant.thirdparty"
                artifactId = b.artifactId
                version = b.bridgedVersion
                artifact(File(workDir, "bridged/${b.artifactId}-${b.bridgedVersion}.aar")) {
                    extension = "aar"
                    builtBy(repackageAll)
                }
                pom {
                    packaging = "aar"
                    name.set("${b.artifactId} (namespace-corrected)")
                    description.set(
                        "Namespace-corrected republish of io.ygdrasil:${b.artifactId}:${b.upstreamVersion}. " +
                            "Manifest package ${b.oldNamespace} -> ${b.newNamespace} so AGP's " +
                            "unique-namespace check passes. Byte-identical otherwise. " +
                            "Temporary bridge — see wgpu4k/wgpu4k and wgpu4k/webgpu-ktypes upstream."
                    )
                    withXml {
                        val pomFile = File(
                            workDir,
                            "upstream/${b.artifactId}-${b.upstreamVersion}.pom",
                        )
                        val deps = upstreamDependenciesNode(pomFile)
                            ?: error("${pomFile.name}: no top-level <dependencies> block found.")
                        val root = asElement()
                        root.appendChild(root.ownerDocument.importNode(deps, true))
                    }
                }
            }
        }
    }
    repositories {
        // Local, file-based repo used to verify the whole chain BEFORE touching GitHub
        // Packages, whose versions are immutable.
        maven {
            name = "LocalBridgeRepo"
            url = uri(layout.buildDirectory.dir("local-bridge-repo"))
        }
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/cquemin/Materia")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
