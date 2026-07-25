/**
 * Gradle script to automatically download the Vosk lightweight English model.
 *
 * This downloads "vosk-model-small-en-us-0.15" (~40MB) into the app's
 * assets/model directory. The model is used by WakeWordEngine for
 * offline wake word detection ("Hey Vision").
 *
 * The task only runs if the model directory doesn't already exist,
 * so it won't re-download on every build.
 */

val modelDir = file("src/main/assets/model")

tasks.register("downloadVoskModel") {
    description = "Downloads the Vosk small English model for offline wake word detection"
    group = "visionaid"

    // Only run if the model directory doesn't exist
    onlyIf { !modelDir.exists() }

    doLast {
        val modelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        val zipFile = file("${layout.buildDirectory.get().asFile}/vosk-model.zip")

        println("Downloading Vosk model from $modelUrl...")
        ant.invokeMethod("get", mapOf(
            "src" to modelUrl,
            "dest" to zipFile.absolutePath,
            "verbose" to true
        ))

        println("Extracting model to assets/model/...")
        copy {
            from(zipTree(zipFile))
            into(file("src/main/assets"))
        }

        // The zip extracts as "vosk-model-small-en-us-0.15", rename to "model"
        val extractedDir = file("src/main/assets/vosk-model-small-en-us-0.15")
        if (extractedDir.exists()) {
            extractedDir.renameTo(modelDir)
            println("Model installed at: ${modelDir.absolutePath}")
        }

        // Clean up zip
        zipFile.delete()
    }
}

// Hook into the build process — download model before merging assets
tasks.matching { it.name == "mergeDebugAssets" || it.name == "mergeReleaseAssets" }.configureEach {
    dependsOn("downloadVoskModel")
}
