# Hangul legends and buffered compatibility mode

This document describes the initial experimental implementation for editors that display or accept Latin input but mishandle Android Hangul composing spans. The feature is disabled by default and currently targets the standard modern two-set (`Dubeolsik`) layout.

## Architecture

The implementation deliberately keeps layout presentation, Hangul composition, and Android editor delivery separate.

| Layer | Module and key files | Responsibility |
| --- | --- | --- |
| Keyboard UI | `:app`, `TextKeyboard.kt`, `KeyboardWindow.kt`, `HangulKeyLegends.kt` | Draw visible key legends and send virtual QWERTY keysyms |
| Hangul engine | `:plugin:hangul`, `fcitx5-hangul/src/engine.cpp`, libhangul | Convert QWERTY key positions into Hangul preedit and commit strings |
| Compatibility buffer | `:app`, `BufferedInputController.kt`, `BufferedHangulMode.kt` | Keep finalized text and current Hangul preedit outside the target editor |
| Delivery | `:app`, `FcitxInputMethodService.kt` | Submit one completed segment with an explicitly selected transport |
| Clipboard history guard | `:app`, `ClipboardMarkers.kt`, `ClipboardManager.kt` | Prevent transient transport clips from entering Fcitx clipboard history |

The normal event path is:

```text
TextKeyboard / physical key
  -> Fcitx.sendKey
  -> AndroidFrontend
  -> fcitx5-hangul / libhangul
  -> Fcitx CommitString, InputPanel, or forwarded Key event
  -> FcitxInputMethodService
  -> target InputConnection
```

The Hangul plugin is a separate APK. A debug main APK must be used with the debug Hangul plugin APK so their package variant and signing identity match.

## Korean key legends

`KeyboardWindow` resolves the active Hangul input method configuration through `getImConfig(<unique name>)`, then reads `cfg/Keyboard`. `TextKeyboard` changes only the visible alphabet labels and popup previews. The key action remains the original Latin QWERTY keysym because libhangul consumes physical key positions.

The initial mapping supports only `Dubeolsik`:

- Normal key labels show `ㅂㅈㄷㄱㅅ...`.
- One-shot Shift shows `ㅃㅉㄸㄲㅆ`, `ㅒ`, and `ㅖ` where applicable.
- Caps Lock keeps normal Hangul labels because fcitx5-hangul explicitly reverses the Latin Caps Lock transformation before passing the keysym to libhangul.
- Unknown layouts fail safely to Latin labels.

`Dubeolsik Yetgeul`, all three-set layouts, `Ahnmatae`, and other layouts are not aliases of modern Dubeolsik. Three-set layouts also require keys outside the current 26-key alphabet surface, so they need dedicated keyboard definitions and action maps.

## Buffered compatibility mode

Enable **Settings > Advanced > Buffered Hangul compatibility mode**, then select a delivery method. The policy activates only for an input method whose addon is `hangul` and whose language starts with `ko`.

While active:

1. The service removes the Fcitx `Preedit` capability. Hangul preedit is therefore rendered in Fcitx's own input panel instead of being sent with `InputConnection.setComposingText()`.
2. `CommitString` events are captured in an in-memory prefix instead of being sent to the editor.
3. The keyboard UI displays `captured prefix + current engine preedit` as one internal composition.
4. Backspace deletes one Unicode code point from the captured prefix when the engine has no remaining preedit.
5. A forwarded Unicode delimiter (for example space, a number, or punctuation) is appended and submits the segment. Return and left/right arrows submit first, then perform their editor action.
6. Input-method changes and input-view shutdown submit the current segment, reset the Hangul engine, and clear the session so text cannot leak into a later editor.
7. If the target reports an unexpected selection change while text is still buffered, the unsent segment is discarded. Once the editor has moved its cursor there is no reliable way to recover the original insertion anchor without reintroducing composing spans.

The buffer is process memory only. It is not persisted across service death.

## Delivery methods

Delivery is explicit. There is no automatic paste-to-commit fallback because Android's remote `performContextMenuAction()` Boolean acknowledges dispatch; it does not prove that the editor pasted the text. Automatically retrying with `commitText()` could duplicate input.

A `false` return is still a definite dispatch failure, so the buffer is preserved for retry. A `true` return only means the request was accepted for dispatch. This policy is applied to system paste, Direct commit, and the modified key-down that triggers Ctrl+V. Key-release failures after an accepted Ctrl+V key-down are ambiguous and do not trigger a retry. Physical or configured Ctrl/Alt/Meta/Super shortcuts first flush any pending Hangul with Direct commit and are then forwarded; this avoids turning shortcut Unicode values into literal text and avoids clipboard collisions with Ctrl+V.

### System paste

The service writes the complete segment to the Android clipboard and invokes `performContextMenuAction(android.R.id.paste)`. This is the preferred first test for standard editors whose Hangul composing support is broken.

The clip is marked sensitive where the Android API supports it and tagged so Fcitx's own clipboard history ignores it. The submitted text intentionally remains in the system clipboard: restoring the previous clip immediately races the asynchronous remote input connection and can paste the wrong value.

### Ctrl+V

The service prepares the same transient clipboard entry and sends a Ctrl+V key combination. This is intended for remote-desktop and raw-key surfaces that implement a paste shortcut but do not expose a standard Android paste action.

### Direct commit

The service sends the completed segment once with `commitText()`. This does not bypass a completely broken `InputConnection`, but it avoids Hangul composing spans and does not use the global clipboard.

Password or sensitive editor fields always force Direct commit, regardless of the selected transport.

## Windows build setup

Enable Windows Developer Mode and Git symlink support before cloning:

```powershell
git config --global core.symlinks true
git clone --recurse-submodules https://github.com/fcitx5-android/fcitx5-android.git D:\workspace\fcitx5-android
```

Install MSYS2 plus the native configuration tools:

```powershell
winget install --exact --id MSYS2.MSYS2
C:\msys64\usr\bin\pacman.exe -Syu --noconfirm
# Run the update a second time if the first run upgrades msys2-runtime and exits.
C:\msys64\usr\bin\pacman.exe -Syu --noconfirm
C:\msys64\usr\bin\pacman.exe -S --needed --noconfirm `
  mingw-w64-ucrt-x86_64-gettext `
  mingw-w64-ucrt-x86_64-extra-cmake-modules `
  mingw-w64-ucrt-x86_64-pkgconf
```

Install the versions pinned by `build-logic/convention/src/main/kotlin/Versions.kt`:

```powershell
$SdkManager = "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat"
& $SdkManager --install `
  "platforms;android-36" `
  "build-tools;36.1.0" `
  "ndk;28.0.13004108" `
  "cmake;3.31.6" `
  "platform-tools"
```

Use JDK 17 and make the MSYS2 UCRT64 tools visible to Gradle:

```powershell
Set-Location D:\workspace\fcitx5-android
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.14.7-hotspot'
$env:Path = "C:\msys64\ucrt64\bin;$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
```

For a normal modern phone, limit development builds to arm64:

```powershell
.\gradlew.bat :app:testDebugUnitTest -PbuildABI=arm64-v8a
.\gradlew.bat :app:lintDebug :plugin:hangul:lintDebug -PbuildABI=arm64-v8a
.\gradlew.bat :app:assembleDebug :plugin:hangul:assembleDebug -PbuildABI=arm64-v8a
```

Keep lint and APK assembly in separate Gradle invocations. With the current upstream task graph, selecting `:plugin:hangul:generateDataDescriptor` through assembly and `:plugin:hangul:generateDebugLintReportModel` in the same invocation triggers Gradle's implicit-dependency validation.

With a device connected through USB or wireless debugging:

```powershell
adb devices -l
.\gradlew.bat :app:installDebug :plugin:hangul:installDebug -PbuildABI=arm64-v8a
```

Then enable the debug Fcitx input method in Android settings, open the debug app, add the Hangul input method, and select `Dubeolsik` in its options.

## Verification status

The initial branch was verified on Windows with JDK 17, SDK 36, Build Tools 36.1.0, NDK 28.0.13004108, CMake 3.31.6, and MSYS2 UCRT64. The debug APKs were also installed on a Samsung SM-F956N running Android 16 (API 36).

- New unit tests for Dubeolsik labels, Shift labels, safe layout fallback, capability masking, buffered concatenation, Unicode code-point deletion, and numeric-password clipboard protection pass.
- `:app:assembleDebug -PbuildABI=arm64-v8a` passes.
- `:plugin:hangul:assembleDebug -PbuildABI=arm64-v8a` passes, including the native fcitx5-hangul build.
- `:plugin:hangul:lintDebug -PbuildABI=arm64-v8a` passes.
- Upstream `:app:lintDebug` does not currently have a baseline and fails with 266 existing errors and 49 warnings. The generated report contains no finding in the files or resources changed for this feature.
- On the device, normal Dubeolsik legends and the one-shot Shift legends rendered correctly.
- In Android Settings search, typing `한글` left the target editor untouched while the text remained in Fcitx's internal panel. Pressing Space then inserted exactly one `한글 ` segment through System paste and cleared the internal panel.
- The same device also passed a keyboard hide/show lifecycle check: hiding the keyboard submitted `한글` exactly once, reopening the same editor kept buffered mode active, and the next internal `테스트` segment remained out of the target until Space produced `한글 테스트 `.
- The full upstream app unit-test task currently has one unrelated pre-existing failure: `ThemeSerializationTest.version2` expects theme version 2.0 not to migrate, while `CustomThemeSerializer.CURRENT_VERSION` is 2.1. The feature-specific test selection passes.

## Device test matrix

Test every transport independently; do not infer success from the Android API return value.

| Surface | System paste | Ctrl+V | Direct commit | Checks |
| --- | --- | --- | --- | --- |
| Android `EditText` | Required | Optional | Required | One copy only; cursor after text |
| Jetpack Compose text field | Required | Optional | Required | No composing text sent to editor |
| WebView input/contenteditable | Required | Optional | Required | Space, punctuation, Return |
| Known broken-IME app | Required | Required | Required | Korean syllable integrity |
| Remote desktop/client | Try | Required | Try | Host receives one complete segment |
| Game/Canvas/Unity field | Try | Try | Try | May expose no usable text endpoint |
| Password field | Must not use | Must not use | Required | Clipboard remains untouched |

Also verify Backspace at every Hangul composition stage, one-shot Shift and Caps Lock, input-method switching, editor focus changes, app background/foreground, rotation, long segments, emoji, hardware keyboard input, and service/process restart.

## Known risks and remaining work

- System paste can be dispatched successfully while the target ignores it; Android exposes no reliable editor-result signal here.
- System paste and Ctrl+V replace the user's global clipboard and leave the submitted text there. Sensitive fields avoid this path, but users must still understand the behavior.
- Canvas, OpenGL, Unity, games, and remote video surfaces may provide neither a standard paste action nor a usable `InputConnection`.
- Focus and selection behavior still requires validation on real problem apps. A failed delivery during focus loss is cleared to prevent cross-editor text leakage.
- Moving the target cursor during an internal composition intentionally discards the unsent segment; preserving the old anchor would require a target-visible composing marker or editor-specific selection choreography.
- Physical-keyboard handling is implemented for forwarded text/navigation keys but has not yet been exercised on a device.
- Lifecycle, shortcut ordering, numeric-password privacy, and physical key down/up behavior currently have code review coverage but still need Android instrumentation tests with a recording `InputConnection`.
- Modern Dubeolsik is the only localized key layout. Yetgeul and three-set layouts need accurate dedicated definitions.
- Add instrumentation tests with a recording `InputConnection`, plus an explicit user-visible submit/retry control if real-app testing shows that delimiter-based submission is insufficient.
