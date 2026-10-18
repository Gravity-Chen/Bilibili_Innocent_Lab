import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins { id("com.android.application") }

val shadowhookArchive = configurations.create("shadowhookArchive") {
    isCanBeConsumed = false
    isTransitive = false
}
val xposedApiArchive = configurations.create("xposedApiArchive") {
    isCanBeConsumed = false
    isTransitive = false
}

abstract class NativeEntrySpecTask : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.NONE) abstract val nativeAnchor: DirectoryProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val ownedCopy: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val shadowAar: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val xposedApiJar: RegularFileProperty
    @get:OutputDirectory abstract val javaDirectory: DirectoryProperty
    @get:OutputFile abstract val dependencyPaths: RegularFileProperty
    @TaskAction fun generate() {
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        check(hash(shadowAar.get().asFile.readBytes()) == "2cad01ff4d59542958775e19f281981a7e84b39670b5fae75905b8511e10c7cd")
        check(hash(xposedApiJar.get().asFile.readBytes()) == "423484a6e1807e7a423c4b88fcd8176d104318259d91791877fed88fe91479d0")
        ZipFile(xposedApiJar.get().asFile).use { archive ->
            check(hash(archive.getInputStream(archive.getEntry("classes.jar")).use { it.readBytes() }) ==
                "a515dd7a53cd7a47c05e101dff77d61acb3091a97b20a885b9ea3494412db985")
        }
        dependencyPaths.get().asFile.apply {
            parentFile.mkdirs()
            writeText(xposedApiJar.get().asFile.absolutePath + "\n" + shadowAar.get().asFile.absolutePath + "\n")
        }
        val declarations = listOf("arm64-v8a", "armeabi-v7a").map { abi ->
            val file = ownedCopy.file("$abi/libinnocent_shadowhook_master_probe.so").get().asFile
            val bytes = file.readBytes()
            check(bytes.size in 1..262144)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            "        if (\"$abi\".equals(abi)) return new NativeLibrarySpec(\"$abi\", ${bytes.size}, \"$hash\");"
        }
        val dependencies = ZipFile(shadowAar.get().asFile).use { archive ->
            listOf("libshadowhook.so", "libshadowhook_nothing.so").map { name ->
                val cases = listOf("arm64-v8a", "armeabi-v7a").map { abi ->
                    val bytes = archive.getInputStream(archive.getEntry("jni/$abi/$name")).use { it.readBytes() }
                    "        if (\"$name\".equals(name) && \"$abi\".equals(abi)) return new NativeLibrarySpec(\"$name\", \"$abi\", ${bytes.size}, \"${hash(bytes)}\");"
                }
                cases.joinToString("\n")
            }.joinToString("\n")
        }
        val file = javaDirectory.file("research/innocent/frame/observer/NativeLibrarySpecs.java").get().asFile
        file.parentFile.mkdirs()
        file.writeText("""package research.innocent.frame.observer;
final class NativeLibrarySpecs {
    static NativeLibrarySpec forAbi(String abi) {
${declarations.joinToString("\n")}
        throw new IllegalArgumentException("unsupported_abi");
    }
    static NativeLibrarySpec dependency(String name, String abi) {
$dependencies
        throw new IllegalArgumentException("unsupported_dependency");
    }
}
""")
    }
}

android {
    namespace = "research.innocent.frame.observer"
    compileSdk = 37
    ndkVersion = "29.0.14206865"
    defaultConfig {
        applicationId = "research.innocent.frame.observer"
        minSdk = 27
        targetSdk = 37
        versionCode = 16
        versionName = "0.9-shadowhook-master"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.31.5" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { prefab = true }
    buildTypes { debug { isDebuggable = true; isMinifyEnabled = false }; release { isMinifyEnabled = false } }
    packaging.jniLibs {
        useLegacyPackaging = true
        // The tiny C library is stripped at link time; preserve its exact build-owned bytes.
        keepDebugSymbols += "**/libinnocent_shadowhook_master_probe.so"
        keepDebugSymbols += "**/libshadowhook_nothing.so"
    }
}
androidComponents.onVariants { variant ->
    val copy = layout.buildDirectory.dir("intermediates/native-entry-copy/${variant.name}")
    variant.externalNativeBuild!!.arguments.add(copy.map { "-DENTRY_PROBE_COPY=${it.asFile.invariantSeparatorsPath}" })
    val task = tasks.register<NativeEntrySpecTask>("generate${variant.name.replaceFirstChar(Char::uppercaseChar)}NativeEntrySpec") {
        nativeAnchor.set(variant.artifacts.get(SingleArtifact.MERGED_NATIVE_LIBS))
        ownedCopy.set(copy)
        shadowAar.set(rootProject.layout.file(provider { shadowhookArchive.singleFile }))
        xposedApiJar.set(rootProject.layout.file(provider { xposedApiArchive.singleFile }))
        dependencyPaths.set(layout.buildDirectory.file("probe-dependencies/${variant.name}.txt"))
        javaDirectory.set(layout.buildDirectory.dir("generated/native-entry/${variant.name}/java"))
    }
    variant.sources.java!!.addGeneratedSourceDirectory(task, NativeEntrySpecTask::javaDirectory)
}
dependencies {
    add(xposedApiArchive.name, "io.github.libxposed:api:102.0.0")
    add(shadowhookArchive.name, "com.bytedance.android:shadowhook:2.0.1@aar")
    compileOnly(files(xposedApiArchive))
    implementation(files(shadowhookArchive))
}
