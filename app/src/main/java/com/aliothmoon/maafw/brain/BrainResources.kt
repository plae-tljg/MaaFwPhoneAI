package com.aliothmoon.maafw.brain

import android.content.Context
import java.io.File

/**
 * Learned-template bundles.
 *
 * MaaFramework loads resource images once per bundle. Templates created
 * after that aren't visible until the resource is reloaded, so each learned
 * batch gets a versioned bundle path; `MaaFrameworkRunnerPort` reloads the
 * resource whenever the path list changes.
 */
object BrainResources {

    fun piRoot(context: Context): File = File(context.getExternalFilesDir(null), "pi")

    fun bundleVersion(db: BrainDb): Int = db.setting("brain_bundle_version", "0").toIntOrNull() ?: 0

    fun bundleDir(context: Context, version: Int): File =
        File(piRoot(context), "brain/res_$version")

    fun nextVersion(db: BrainDb): Int {
        val next = bundleVersion(db) + 1
        db.setSetting("brain_bundle_version", next.toString())
        return next
    }

    fun resourcePaths(context: Context, db: BrainDb): List<String> {
        val version = bundleVersion(db)
        val learned = "brain/res_$version"
        val paths = mutableListOf<String>()
        // Packed OCR model root (proto/bundle/model -> PI/brain_ocr/model).
        if (File(piRoot(context), "brain_ocr").isDirectory) paths += "brain_ocr"
        paths += "resource"
        if (version > 0 && File(piRoot(context), learned).isDirectory) paths += learned
        return paths
    }

    fun saveTemplate(context: Context, version: Int, name: String, source: String,
                     cx: Int, cy: Int): Boolean {
        val dir = File(bundleDir(context, version), "image").apply { mkdirs() }
        return ImageTools.cropTemplate(source, cx, cy, File(dir, name))
    }
}
