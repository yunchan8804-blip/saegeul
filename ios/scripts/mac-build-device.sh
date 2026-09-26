#!/bin/bash
# 새글 iOS — 맥미니 SSH(비대화형) 세션에서 실기기 빌드·설치.
#
# ssh 세션에서는 로그인 키체인이 잠겨 있어 codesign이 errSecInternalComponent로 실패한다(2026-09-14 실측).
# CI 관례대로 전용 빌드 키체인을 비밀번호 파일로 잠금 해제하고 기본 키체인으로 지정한 뒤 빌드한다.
# 비밀번호 파일은 저장소 밖(gitignored)에 두며 이 스크립트는 경로만 참조한다.
#
# 사용: scripts/mac-build-device.sh [build|install|launch|uitest]   (기본 install)
#   build   — 실기기용 Debug 빌드만
#   install — 빌드 후 devicectl로 아이폰에 설치
#   launch  — install 후 호스트 앱 실행
#   uitest  — 실기기에서 SaegeulUITests 실행 (설정 앱 자동화로 키보드 추가 → 호스트 앱에서 ㄱ+ㅏ=가 확인)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ACTION="${1:-install}"
DEVICE="${SAEGEUL_IOS_DEVICE:-8364243E-3479-5C43-B825-E11EED1A4A37}"
BUILD_KEYCHAIN="${SAEGEUL_BUILD_KEYCHAIN:-$HOME/Library/Keychains/haramlog-build.keychain-db}"
KEYCHAIN_PW_FILE="${SAEGEUL_BUILD_KEYCHAIN_PW_FILE:-$HOME/workspace/HaramLog-w11/.secrets/apple/build-keychain.pw}"
DERIVED="$ROOT/DerivedData"
APP_BUNDLE_ID="net.chanpaca.saegeul.ios"

restore_default_keychain() {
  security default-keychain -s "$HOME/Library/Keychains/login.keychain-db" >/dev/null 2>&1 || true
}

setup_build_keychain() {
  [[ -f "$KEYCHAIN_PW_FILE" ]] || { echo "[keychain] 비밀번호 파일이 없습니다: $KEYCHAIN_PW_FILE" >&2; exit 2; }
  [[ -f "$BUILD_KEYCHAIN" ]] || { echo "[keychain] 빌드 키체인이 없습니다: $BUILD_KEYCHAIN" >&2; exit 2; }
  security unlock-keychain -p "$(cat "$KEYCHAIN_PW_FILE")" "$BUILD_KEYCHAIN"
  security set-keychain-settings -lut 21600 "$BUILD_KEYCHAIN"
  local existing
  existing="$(security list-keychains -d user | tr -d '"' | grep -v "$BUILD_KEYCHAIN" | xargs)"
  # shellcheck disable=SC2086
  security list-keychains -d user -s "$BUILD_KEYCHAIN" $existing
  security default-keychain -s "$BUILD_KEYCHAIN"
  # 빌드 키체인이 기본으로 남으면 시스템 프로세스가 GUI 암호 프롬프트를 띄우므로 종료 시 되돌린다.
  trap restore_default_keychain EXIT
}

setup_build_keychain

if [[ "$ACTION" == "uitest" ]]; then
  RESULT="$ROOT/outputs/SaegeulUITests-$(date +%Y%m%d-%H%M%S).xcresult"
  mkdir -p "$ROOT/outputs"
  echo "[uitest] xcodebuild test → $DEVICE, 결과: $RESULT"
  set +e
  xcodebuild     -project "$ROOT/Saegeul.xcodeproj"     -scheme Saegeul     -configuration Debug     -destination "id=$DEVICE"     -derivedDataPath "$DERIVED"     -resultBundlePath "$RESULT"     -allowProvisioningUpdates     -only-testing:SaegeulUITests     test 2>&1 | tee "$ROOT/xcodebuild-uitest.log" | grep -E "error:|Test Case|Test Suite|Executed|BUILD|TEST" || true
  set -e
  echo "[uitest] 전체 로그: $ROOT/xcodebuild-uitest.log"
  grep -q "\*\* TEST SUCCEEDED \*\*" "$ROOT/xcodebuild-uitest.log"
  exit $?
fi

echo "[build] xcodebuild → $DEVICE"
xcodebuild \
  -project "$ROOT/Saegeul.xcodeproj" \
  -scheme Saegeul \
  -configuration Debug \
  -destination "id=$DEVICE" \
  -derivedDataPath "$DERIVED" \
  -allowProvisioningUpdates \
  build 2>&1 | tee "$ROOT/xcodebuild-device.log" | grep -E "error:|warning: |BUILD (SUCCEEDED|FAILED)|CodeSign|Signing Identity|Provisioning Profile" || true

if ! grep -q "\*\* BUILD SUCCEEDED \*\*" "$ROOT/xcodebuild-device.log"; then
  echo "[build] 실패. 전체 로그: $ROOT/xcodebuild-device.log" >&2
  exit 1
fi

APP="$DERIVED/Build/Products/Debug-iphoneos/Saegeul.app"
echo "[build] 산출물: $APP"
ls "$APP/PlugIns"

[[ "$ACTION" == "build" ]] && exit 0

echo "[install] devicectl install"
xcrun devicectl device install app --device "$DEVICE" "$APP"
xcrun devicectl device info apps --device "$DEVICE" --bundle-id "$APP_BUNDLE_ID"

[[ "$ACTION" == "install" ]] && exit 0

echo "[launch] $APP_BUNDLE_ID"
xcrun devicectl device process launch --device "$DEVICE" "$APP_BUNDLE_ID"
