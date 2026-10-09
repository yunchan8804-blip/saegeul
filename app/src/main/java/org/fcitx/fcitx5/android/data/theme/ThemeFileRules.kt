/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.data.theme

import java.io.File

/**
 * Pure rules for theme names and theme files. They hold no Android state so the
 * import path checks can be unit tested on the JVM.
 */
object ThemeFileRules {

    const val MAX_THEME_NAME_LENGTH = 128

    private const val MAX_EXPORT_FILE_NAME_LENGTH = 64

    private const val SRC_IMAGE_MARKER = "-src"

    private const val UNSAFE_FILE_NAME_CHARS = "/\\:*?\"<>|"

    /**
     * A theme name becomes part of a file name (`<name>.json`), so it must stay a single,
     * plain path segment.
     */
    fun isSafeThemeName(name: String): Boolean =
        name.isNotBlank() &&
            name.length <= MAX_THEME_NAME_LENGTH &&
            !name.contains("..") &&
            name.none { it == '/' || it == '\\' || it.isISOControl() }

    /**
     * Names an imported theme must not take: builtin themes, shop themes and the Monet themes.
     * [builtinNames] is passed in because the builtin list lives in [ThemeManager], whose
     * initializer needs an Android context.
     */
    fun isReservedThemeName(name: String, builtinNames: Collection<String>): Boolean =
        name in builtinNames ||
            ThemeShopCatalog.find(name) != null ||
            name == Theme.Monet.nameOf(isDark = false) ||
            name == Theme.Monet.nameOf(isDark = true)

    /**
     * Keeps only the file name of an image path found in an imported theme, so the image is
     * always placed directly under the theme directory.
     */
    fun backgroundImageFileName(path: String): String? {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        if (name.isEmpty() || name == "." || name == "..") return null
        if (name.any { it.isISOControl() }) return null
        return name
    }

    fun isInside(dir: File, file: File): Boolean =
        file.canonicalPath.startsWith(dir.canonicalPath + File.separator)

    /** [fileName] under [dir], or null when its canonical path would leave [dir]. */
    fun resolveInside(dir: File, fileName: String): File? =
        File(dir, fileName).takeIf { isInside(dir, it) }

    /**
     * The source image of a theme is stored as `<id>-src` and gains its extension once the
     * image is saved. An image saved before already carries one, so it is replaced instead of
     * appended to.
     */
    fun srcFileWithExtension(file: File, extension: String?): File {
        if (extension.isNullOrEmpty()) return file
        val name = file.name
        val markerEnd = name.lastIndexOf(SRC_IMAGE_MARKER)
            .takeIf { it >= 0 }
            ?.plus(SRC_IMAGE_MARKER.length)
        val newName = when {
            markerEnd != null -> name.substring(0, markerEnd) + "." + extension
            name.endsWith(".$extension") -> name
            else -> "$name.$extension"
        }
        return File(file.parentFile, newName)
    }

    /** A file name for exporting a theme, derived from the name shown to the user. */
    fun exportFileName(displayName: String): String {
        val cleaned = displayName
            .filterNot { it in UNSAFE_FILE_NAME_CHARS || it.isISOControl() }
            .trim()
            .trim('.')
            .take(MAX_EXPORT_FILE_NAME_LENGTH)
            .trim()
        return cleaned.ifEmpty { "theme" }
    }
}
