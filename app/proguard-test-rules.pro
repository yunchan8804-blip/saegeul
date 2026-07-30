# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan

# The release AndroidTest APK is loaded before the target APK. Keep the complete desugared JDK
# compatibility runtime because target-app libraries can call members that test shrinking cannot
# observe, and the test APK's copy of j$ shadows the target APK's copy at runtime.
-keep class j$.** { *; }
