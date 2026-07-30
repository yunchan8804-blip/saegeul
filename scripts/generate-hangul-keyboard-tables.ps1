# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan

param(
    [string]$Revision = "a34aef73378c0992316861bbf13fc914ee7577d9",
    [string]$Output = "app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/HangulKeyboardTables.generated.kt"
)

$ErrorActionPreference = "Stop"
$sourceUrl = "https://raw.githubusercontent.com/libhangul/libhangul/$Revision/hangul/hangulkeyboard.h"
$source = (Invoke-WebRequest -UseBasicParsing $sourceUrl).Content

$layouts = [ordered]@{
    "Dubeolsik" = "2"
    "Dubeolsik Yetgeul" = "2y"
    "Sebeolsik 390" = "390"
    "Sebeolsik Final" = "3final"
    "Sebeolsik Noshift" = "3sun"
    "Sebeolsik Yetgeul" = "3yet"
    "Sebeolsik Dubeol Layout" = "32"
    "Romaja" = "romaja"
    "Ahnmatae" = "ahn"
}

$generated = [System.Text.StringBuilder]::new()
[void]$generated.AppendLine('/*')
[void]$generated.AppendLine(' * SPDX-License-Identifier: LGPL-2.1-or-later')
[void]$generated.AppendLine(' * Generated from libhangul. Do not edit by hand.')
[void]$generated.AppendLine(" * Source revision: $Revision")
[void]$generated.AppendLine(' */')
[void]$generated.AppendLine('package org.fcitx.fcitx5.android.input.keyboard')
[void]$generated.AppendLine()
[void]$generated.AppendLine('internal object HangulKeyboardTables {')
[void]$generated.AppendLine("    const val SourceRevision = `"$Revision`"")
[void]$generated.AppendLine('    val byLayout: Map<String, IntArray> = mapOf(')

foreach ($entry in $layouts.GetEnumerator()) {
    $escapedId = [regex]::Escape($entry.Value)
    $pattern = "(?s)static const ucschar hangul_keyboard_table_${escapedId}\[\] = \{(.*?)\n\};"
    $match = [regex]::Match($source, $pattern)
    if (-not $match.Success) { throw "Missing libhangul table: $($entry.Value)" }
    $values = [regex]::Matches($match.Groups[1].Value, '0x([0-9a-fA-F]{4,6})') |
        ForEach-Object { "0x$($_.Groups[1].Value.ToLowerInvariant())" }
    if ($values.Count -ne 128) { throw "Expected 128 values for $($entry.Value), got $($values.Count)" }
    [void]$generated.AppendLine("        `"$($entry.Key)`" to intArrayOf(")
    for ($offset = 0; $offset -lt $values.Count; $offset += 8) {
        $end = [Math]::Min($offset + 7, $values.Count - 1)
        [void]$generated.AppendLine("            " + (($values[$offset..$end] -join ', ') + ','))
    }
    [void]$generated.AppendLine('        ),')
}

[void]$generated.AppendLine('    )')
[void]$generated.AppendLine('}')

$outputPath = Join-Path (Get-Location) $Output
$outputDir = Split-Path -Parent $outputPath
New-Item -ItemType Directory -Force $outputDir | Out-Null
[System.IO.File]::WriteAllText($outputPath, $generated.ToString(), [System.Text.UTF8Encoding]::new($false))
Write-Output "Generated $Output from libhangul $Revision"
