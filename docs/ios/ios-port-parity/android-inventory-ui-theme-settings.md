# Android 테마·설정·호스트 앱 인벤토리 (2026-09-14)

이 문서는 새글 Android 앱(`net.chanpaca.saegeul`)의 테마 엔진, 설정 화면 계층, 호스트 앱 화면, 브랜드 토큰, 접근성·국제화 현황을 코드 근거와 함께 정리한 인벤토리다. iOS 1:1 이식 격차표의 기준선으로 쓴다. 추측 없이 확인한 코드만 기재했다.

근거 경로는 `app/src/main/java/org/fcitx/fcitx5/android/` 이하를 기준으로 축약했다. 예: `data/theme/Theme.kt:21`.

---

## 0. 중요 전제

설정 화면에 XML preference 리소스가 전혀 없다. `app/src/main/res/xml/`에는 백업 규칙(`data_extraction_rules.xml`, `full_backup_content.xml`), IME 선언(`input_method.xml`), 위젯 정보(`vault_widget_info.xml`), GIF 파일 경로(`gif_file_paths.xml`)만 있다. 모든 설정 화면은 Kotlin 코드로 구성된다. 내비게이션 그래프도 XML이 아니라 `ui/main/settings/SettingsRoute.kt`의 Kotlin DSL이다.

---

## 1. 테마 엔진

### 1.1 테마 데이터 모델

| 기능 | Android 근거 파일:줄 | 설정 키/이름 | 비고 |
|---|---|---|---|
| Theme sealed class (Parcelable + Serializable) | `data/theme/Theme.kt:21-22` | - | 3개 하위 타입 |
| 필수 색상 슬롯 22개 | `data/theme/Theme.kt:27-56` | backgroundColor, barColor, keyboardColor, keyBackgroundColor, keyTextColor, candidateTextColor, candidateLabelColor, candidateCommentColor, altKeyBackgroundColor, altKeyTextColor, accentKeyBackgroundColor, accentKeyTextColor, keyPressHighlightColor, keyShadowColor, popupBackgroundColor, popupTextColor, spaceBarColor, dividerColor, clipboardEntryColor, genericActiveBackgroundColor, genericActiveForegroundColor + name/isDark | 모든 테마 타입 공통 |
| 확장 필드 5개 (Custom 전용, 기본 null) | `data/theme/Theme.kt:58-62` | keyOverrides, globalKeyStyle, lightingEffect, particleEffect, keyGlowEffect | Builtin/Monet은 항상 null |
| `Theme.Custom` | `data/theme/Theme.kt:68-193` | - | 파일 저장 대상 |
| `Theme.Builtin` | `data/theme/Theme.kt:195-340` | - | 코드 내장, 파일 없음 |
| `Theme.Monet` (Material You 동적 색) | `data/theme/Theme.kt:342-431`, `data/theme/ThemeMonet.kt:18-95` | - | SDK 34+ 실제 Monet, 31-33 근사값, 그 이하 `#769CDF` 정적 팔레트 |
| KeyCustomStyle (키별 스타일 7필드) | `data/theme/Theme.kt:104-114` | keyBackgroundColor, keyTextColor, keyBorderColor, keyGlowColor, keyGlowRadius, cornerRadius, slicedImage | - |
| SlicedImageDef (9-patch) | `data/theme/Theme.kt:116-125` | imagePath, leftSlice, topSlice, rightSlice, bottomSlice, repeatMode(기본 "stretch") | - |
| LightingEffectDef | `data/theme/Theme.kt:127-149` | mode, speed(1.0f), intensity(0.8f), direction("left_to_right"), customColors, reactiveMode("off"), keyTranslucency(0.0f) | `reactive_` 접두사 파싱 로직 포함 |
| ParticleEffectDef | `data/theme/Theme.kt:151-159` | type("off"), particleCount(8), color, lifetimeMs(450L), speed(1.0f) | - |
| KeyGlowDef | `data/theme/Theme.kt:161-168` | enabled(false), glowColor(0), glowRadius(4f), glowSpread(1f) | - |
| CustomBackground | `data/theme/Theme.kt:170-187` | croppedFilePath, srcFilePath, brightness(70), cropRect, cropRotation(0) | `DarkenColorFilter(100-brightness)` 적용 |
| Builtin에서 Custom 파생 (배경 없음) | `data/theme/Theme.kt:274-299` | `deriveCustomNoBackground(name)` | 내장 테마 복제에 사용 |
| Builtin에서 Custom 파생 (배경 포함) | `data/theme/Theme.kt:301-339` | `deriveCustomBackground(...)` | - |
| Monet을 Custom으로 변환 | `data/theme/Theme.kt:405-430` | 이름에 primary 색 hex 접미 | 내보내기용 |

### 1.2 내장 테마 목록

| 테마 이름 | 근거 | isDark | 등록 상태 |
|---|---|---|---|
| HanjiLight (한지) | `data/theme/ThemePreset.kt:10` | false | 등록 |
| DancheongDark (단청) | `data/theme/ThemePreset.kt:37` | true | 등록 |
| BaegjaLight (백자) | `data/theme/ThemePreset.kt:64` | false | 등록 |
| CheongjaDark (청자) | `data/theme/ThemePreset.kt:91` | true | 등록 |
| MidnightOLED (자정) | `data/theme/ThemePreset.kt:118` | true | 등록 |
| SeoulMistGlass (안개) | `data/theme/ThemePreset.kt:145` | - | 등록 |
| MaterialLight | `data/theme/ThemePreset.kt:171` | false | 등록 |
| MaterialDark | `data/theme/ThemePreset.kt:198` | true | 등록 |
| PixelLight | `data/theme/ThemePreset.kt:225` | false | 등록 |
| PixelDark | `data/theme/ThemePreset.kt:252` | true | 등록, `DefaultTheme` |
| DeepBlue | `data/theme/ThemePreset.kt:279` | - | 등록 |
| AMOLEDBlack | `data/theme/ThemePreset.kt:306` | - | 등록 |
| NordLight | `data/theme/ThemePreset.kt:333` | false | 등록 |
| NordDark | `data/theme/ThemePreset.kt:359` | true | 등록 |
| Monokai | `data/theme/ThemePreset.kt:385` | - | 등록 |
| TransparentDark | `data/theme/ThemePreset.kt:414` | true | **미등록** |
| TransparentLight | `data/theme/ThemePreset.kt:443` | false | **미등록** |

`ThemeManager.BuiltinThemes`에 등록된 것은 15개다(`data/theme/ThemeManager.kt:26-42`). TransparentDark/TransparentLight는 정의만 있고 목록에 없어 사용자에게 노출되지 않는다. iOS 이식 시 이 2개는 격차표에서 제외한다.

기본 테마는 `PixelDark`다(`data/theme/ThemeManager.kt:44`).

한국 전통 테마 6종의 브랜드 색은 `docs/wiki/08-Form-Factor-and-Theming.md:44-49`에 문서화돼 있다.

### 1.3 포인트 상점 테마 (광고 수익화 연동)

| 테마 | 표시명 | 가격 | 근거 |
|---|---|---|---|
| SilverMistLight | 은빛 안개 | `PointPricing.NORMAL_THEME_PRICE` | `data/theme/ThemeShopCatalog.kt:23-51` |
| PineDark | 소나무 밤 | NORMAL | `data/theme/ThemeShopCatalog.kt:52-80` |
| RosyDuskDark | 황혼 로즈 | PREMIUM | `data/theme/ThemeShopCatalog.kt:81-109` |
| CeladonJadeLight | 청자 비취 | PREMIUM | `data/theme/ThemeShopCatalog.kt:110-138` |

소유 기록은 `data/theme/ThemeOwnershipStore.kt:13-28`의 별도 SharedPreferences `theme_shop` / 키 `owned_shop_themes`에 저장한다. 기기 간 이전 불가로 설계돼 있다(`docs/business/ad-monetization-avenue-operations.md` 14.2 참조 주석).

구매 다이얼로그와 리워드 광고 적립은 `ui/main/settings/theme/ThemeListFragment.kt:233-281`에 있다. 적립 제한은 하루 3회, 20분 간격이다(`ThemeListFragment.kt:276`).

상점 테마는 `getAllThemes()`에 포함되지만(`data/theme/ThemeManager.kt:54`) 선택 시 소유 여부를 검사해 미소유면 구매 다이얼로그로 빠진다(`ThemeListFragment.kt:207-212`).

### 1.4 테마 직렬화와 파일 관리

| 기능 | 근거 | 비고 |
|---|---|---|
| JSON 스키마 버전 | `data/theme/CustomThemeSerializer.kt:97-98` | CURRENT_VERSION = "3.0", FALLBACK_VERSION = "1.0" |
| 마이그레이션 전략 4단계 | `data/theme/CustomThemeSerializer.kt:55-93` | 3.0(무변환), 2.1(candidate 색 3종 파생), 2.0(popup·generic 색 파생), 1.0(무변환) |
| 직렬화 시 version 주입 / 역직렬화 후 제거 | `data/theme/CustomThemeSerializer.kt:23-40` | 알 수 없는 버전은 error |
| 저장 경로 | `data/theme/ThemeFilesManager.kt:21-23` | `getExternalFilesDir(null)/theme/<name>.json` |
| 배경 이미지 파일 | `data/theme/ThemeFilesManager.kt:25-30` | `<UUID>-cropped.png`, `<UUID>-src` |
| 목록 정렬 | `data/theme/ThemeFilesManager.kt:47` | 최근 수정 순 |
| 디코드 실패 시 건너뜀 | `data/theme/ThemeFilesManager.kt:49-54` | Timber 경고 후 null |
| 이미지 누락 시 색 테마로 강등 | `data/theme/ThemeFilesManager.kt:55-66` | 삭제하지 않고 보존 |
| 마이그레이션 발생 시 파일 재저장 | `data/theme/ThemeFilesManager.kt:68-70` | - |
| ZIP 내보내기 | `data/theme/ThemeFilesManager.kt:78-112` | 내부 절대경로 제거 후 패키징 |
| ZIP 가져오기 | `data/theme/ThemeFilesManager.kt:117-166` | 내장 테마와 이름 충돌 시 거부(`exception_theme_name_clash`) |

### 1.5 다크/라이트 자동 전환

| 기능 | 근거 | 설정 키 | 기본값 |
|---|---|---|---|
| 시스템 주야 모드 따르기 | `data/theme/ThemePrefs.kt:136-141` | `follow_system_dark_mode` | true |
| 라이트 모드 테마 | `data/theme/ThemePrefs.kt:143-149` | `light_mode_theme` | 릴리스 PixelLight, 디버그 MaterialLight |
| 다크 모드 테마 | `data/theme/ThemePrefs.kt:151-157` | `dark_mode_theme` | 릴리스 PixelDark, 디버그 MaterialDark |
| 단일 모드 테마 (UI 없음) | `data/theme/ThemePrefs.kt:130-134` | `normal_mode_theme` | DefaultTheme |
| 활성 테마 평가 | `data/theme/ThemeManager.kt:123-129` | - | followSystem 여부로 분기 |
| 시스템 팔레트 변경 대응 | `data/theme/ThemeManager.kt:147-153` | - | Monet 재생성 후 재평가 |
| 테마 변경 리스너 (WeakHashSet) | `data/theme/ThemeManager.kt:78-90` | - | - |
| 기기 암호화 저장소 동기화 | `data/theme/ThemeManager.kt:155-164` | - | 직접 부팅 지원 |
| 테마 탭 이탈 시 동기화 | `ui/main/settings/theme/ThemeFragment.kt:117-122` | - | SDK 24+ |

### 1.6 키 모양·여백 설정 (ThemePrefs 16개 키)

| 항목 | 설정 키 | 기본값 | 범위 | 근거 |
|---|---|---|---|---|
| 키 테두리 | `key_border` | false | - | `data/theme/ThemePrefs.kt:37` |
| 키 테두리 선 | `key_border_stroke` | false | key_border 켠 경우만 | `data/theme/ThemePrefs.kt:39-42` |
| 키 리플 효과 | `key_ripple_effect` | false | - | `data/theme/ThemePrefs.kt:44` |
| 키 좌우 여백 (세로) | `key_horizontal_margin` | 3 | 0-24 dp | `data/theme/ThemePrefs.kt:50-61` |
| 키 좌우 여백 (가로) | `key_horizontal_margin_landscape` | 3 | 0-24 dp | `data/theme/ThemePrefs.kt:50-61` |
| 키 상하 여백 (세로) | `key_vertical_margin` | 7 | 0-24 dp | `data/theme/ThemePrefs.kt:70-81` |
| 키 상하 여백 (가로) | `key_vertical_margin_landscape` | 4 | 0-24 dp | `data/theme/ThemePrefs.kt:70-81` |
| 키 모서리 반경 | `key_radius` | 4 | 0-48 dp | `data/theme/ThemePrefs.kt:86` |
| 텍스트 편집 버튼 반경 | `text_editing_button_radius` | 8 | 0-48 dp | `data/theme/ThemePrefs.kt:88-89` |
| 클립보드 항목 반경 | `clipboard_entry_radius` | 2 | 0-48 dp | `data/theme/ThemePrefs.kt:91-92` |
| 문장부호 위치 | `punctuation_position` | Bottom | None / Bottom / TopRight | `data/theme/ThemePrefs.kt:94-104` |
| 내비바 배경 | `navbar_background` | SDK27+ Full, 그 외 ColorOnly | None / ColorOnly / Full | `data/theme/ThemePrefs.kt:106-124` |
| 라이트 모드 테마 | `light_mode_theme` | - | - | `data/theme/ThemePrefs.kt:143` |
| 다크 모드 테마 | `dark_mode_theme` | - | - | `data/theme/ThemePrefs.kt:151` |
| 단일 모드 테마 | `normal_mode_theme` | - | - | `data/theme/ThemePrefs.kt:130` |
| 시스템 주야 따르기 | `follow_system_dark_mode` | true | - | `data/theme/ThemePrefs.kt:136` |

`navbar_background`는 SDK 35(VANILLA_ICE_CREAM) 이상에서 강제 edge-to-edge라 UI가 비활성화되고 저장값도 삭제된다(`data/theme/ThemePrefs.kt:117-123`).

### 1.7 테마 편집기 (Custom Theme Studio)

`ui/main/settings/theme/CustomThemeActivity.kt` 한 파일이 1,575줄이며 10개 섹션으로 구성된다.

| 섹션 | 근거 | 내용 |
|---|---|---|
| 1. 스타일 팔레트 | `CustomThemeActivity.kt:332`, `671-723` | 원클릭 프리셋 14종: 한지, 단청, 백자, 청자, 자정 OLED, 안개 Glass, 픽셀 다크, 픽셀 라이트, 머티리얼 다크, 머티리얼 라이트, 노르딕 다크, 노르딕 라이트, 모노카이, 딥블루 |
| 2. RGB 백라이트 (앰비언트 모드) | `CustomThemeActivity.kt:338`, `725-739` | 13종: off, rgb_wave, rgb_breathe, cyberpunk, matrix_flow, neon_pulse, aurora, starlight, ocean_tide, fire_ember, supernova, sakura_breeze, frost_crystal |
| 2-1. 앰비언트 방향 | `CustomThemeActivity.kt:345`, `799-806` | 6종: left_to_right, right_to_left, top_to_bottom, bottom_to_top, diagonal, radial |
| 2-2. 앰비언트 슬라이더 | `CustomThemeActivity.kt:251,261,271`, `1197-1237` | 속도(theme_rgb_speed), 강도(theme_rgb_intensity), 키 투명도(theme_key_translucency) |
| 3. 리액티브 모드 | `CustomThemeActivity.kt:386`, `851-857` | 5종: off, ripple, fade, firework, laser |
| 4. 파티클 효과 | `CustomThemeActivity.kt:392`, `902-908` | 5종: off, star_sparkle, glowing_dust, neon_burst, cosmic_ripple |
| 4-1. 파티클 슬라이더 | `CustomThemeActivity.kt:281,291,301`, `1239-1279` | 개수, 수명, 속도 |
| 5. 키 글로우 | `CustomThemeActivity.kt:434`, `960-1006`, `311` | 글로우 색상 선택 + 반경 슬라이더 |
| 6. 키별 커스터마이징 | `CustomThemeActivity.kt:453`, `1008-1107` | 대상 5종(ALL, button_space, button_return, button_backspace, button_shift) x 색상 9종(초기화 null 포함) |
| 7. 액센트/엔터 색 | `CustomThemeActivity.kt:462`, `1109-1151` | 원형 스와치 13종 (한지 적갈색, 단청 주홍, 백자 코발트, 청자 금색, 자정 네온 제이드, 안개 스카이, 로얄 블루, 에메랄드, 핑크 로즈, 바이올렛, 앰버 옐로우, 퓨어 화이트, 퓨어 블랙) |
| 8. 키보드 바탕색 | `CustomThemeActivity.kt:468`, `1153-1193` | 사각 스와치 10종 (surface + bar 쌍): 백자 오프화이트, 한지 미색, 퓨어 화이트, 단청 묵색, 청자 비색, 자정 OLED, 안개 슬레이트, 픽셀 다크, 머티리얼 다크, 노르딕 다크 |
| 9. 키캡 스타일 | `CustomThemeActivity.kt:474`, `148`, `653-669` | 어두운 키 토글(`R.string.dark_keys`) |
| 10. 배경 이미지 | `CustomThemeActivity.kt:487`, `169-177` | 이미지 선택, 재크롭, 제거 + 밝기 슬라이더(`157`, `1408`) |

부가 사항:

- 실시간 키보드 프리뷰: `ui/main/settings/theme/KeyboardPreviewUi.kt:43`, 편집기에서 `updatePreview()`(`CustomThemeActivity.kt:562`)로 갱신
- 결과 계약 3종: `BackgroundResult.Created` / `Updated` / `Deleted` (`CustomThemeActivity.kt:97-106`)
- 저장/삭제 툴바 메뉴: `CustomThemeActivity.kt:1554-1563`
- 삭제 확인 다이얼로그: `CustomThemeActivity.kt:1540-1549`
- 크롭 실행: `CustomThemeActivity.kt:1445-1461` (`CropImageActivity` 연동)

### 1.8 테마 목록 화면

| 기능 | 근거 | 비고 |
|---|---|---|
| 탭 2개 (테마 / 설정) | `ui/main/settings/theme/ThemeFragment.kt:69-86` | ViewPager2 + TabLayout |
| 상단 고정 프리뷰 | `ui/main/settings/theme/ThemeFragment.kt:60-65` | 0.5배 축소, elevation 4dp |
| 반응형 그리드 | `ui/main/settings/theme/ResponsiveThemeListView.kt:15-32` | 항목 128x92dp, 최소 여백 16dp, 폭에 따라 span 자동 |
| 선택 상태 배지 4종 | `ui/main/settings/theme/ThemeThumbnailUi.kt:42`, `130-136` | Normal, Selected(체크), LightMode(해 아이콘), DarkMode(달 아이콘) |
| 편집 버튼 | `ui/main/settings/theme/ThemeThumbnailUi.kt:58-61`, `ThemeListAdapter.kt:135-137` | Custom 테마만 동작 |
| 길게 눌러 내보내기 | `ui/main/settings/theme/ThemeListAdapter.kt:126-134` | Custom과 Monet(toCustom) 지원 |
| 새 테마 만들기 3경로 | `ui/main/settings/theme/ThemeListFragment.kt:166-205` | 이미지 선택 / 파일 가져오기(zip) / 내장 테마 복제 |
| 내장 테마 복제 다이얼로그 | `ui/main/settings/theme/ThemeListFragment.kt:180-201` | `SimpleThemeListAdapter`, UUID 이름 부여 |
| 주야 모드 켜진 상태에서 선택 시 경고 | `ui/main/settings/theme/ThemeListFragment.kt:213-229` | "비활성화" 선택 시 followSystem 끄고 적용 |
| 신규 항목 UI | `ui/main/settings/theme/NewThemeEntryUi.kt:31-43` | `R.string.new_theme` + plus 아이콘 |
| 항목 간격 데코레이션 | `ui/main/settings/theme/ThemeListItemDecoration.kt` | - |

---

## 2. 설정 화면 계층

### 2.1 최상위 화면 (MainFragment)

| 순서 | 항목 | 라우트 | 아이콘 | 근거 |
|---|---|---|---|---|
| 0 | Typing DNA 카드 (헤더) | 대시보드 Activity | - | `ui/main/MainFragment.kt:55-57` |
| **언어 및 입력** (`R.string.languages_and_input`) | | | | `ui/main/MainFragment.kt:58` |
| 1 | 입력기 | InputMethodList | ic_baseline_language_24 | `ui/main/MainFragment.kt:59-63` |
| **제품 설정** (`R.string.product_settings`) | | | | `ui/main/MainFragment.kt:65` |
| 2 | 테마 | Theme | ic_baseline_palette_24 | `ui/main/MainFragment.kt:66-70` |
| 3 | 가상 키보드 | VirtualKeyboard | ic_baseline_keyboard_24 | `ui/main/MainFragment.kt:71-75` |
| 4 | 후보 창 | CandidatesWindow | ic_baseline_list_alt_24 | `ui/main/MainFragment.kt:76-80` |
| 5 | 클립보드 | Clipboard | ic_clipboard | `ui/main/MainFragment.kt:81-85` |
| 6 | 이모지와 기호 | Symbol | ic_baseline_emoji_symbols_24 | `ui/main/MainFragment.kt:86-90` |
| 7 | 플러그인 (개발 빌드만) | Plugin | ic_baseline_android_24 | `ui/main/MainFragment.kt:91-97` |
| 8 | 고급 | Advanced | ic_baseline_more_horiz_24 | `ui/main/MainFragment.kt:98-102` |
| 9 | 개인정보·AI | PrivacyAi | ic_baseline_auto_awesome_24 | `ui/main/MainFragment.kt:103-107` |
| 10 | 앱 프로필 | AppProfiles | ic_baseline_settings_24 | `ui/main/MainFragment.kt:108-112` |
| 11 | 개인 사전 | PersonalDictionary | ic_baseline_library_books_24 | `ui/main/MainFragment.kt:113-117` |
| **엔진 개발 도구** (개발 빌드만) | | | | `ui/main/MainFragment.kt:119-132` |
| 12 | 전역 옵션 | GlobalConfig | ic_baseline_tune_24 | `ui/main/MainFragment.kt:121-125` |
| 13 | 애드온 | AddonList | ic_baseline_extension_24 | `ui/main/MainFragment.kt:126-130` |

빌드별 노출은 `ui/main/ProductSurfacePolicy.kt:13-24`가 `BuildConfig.SHOW_DEVELOPER_SURFACES` 하나로 3개 플래그(showRawEngineSettings, showPluginManager, showDeveloperTools)를 통제한다. 릴리스 빌드에서는 플러그인 관리자, 원시 엔진 설정, 개발자 도구가 전부 숨겨진다. 릴리스 최상위 항목은 11개다.

### 2.2 툴바 메뉴

| 항목 | 아이콘 | 표시 조건 | 근거 |
|---|---|---|---|
| 저장 | ic_baseline_save_24 | ViewModel 리스너 존재 시 | `ui/main/MainActivity.kt:190-195` |
| FAQ | 없음 (오버플로) | aboutButton 활성 시 | `ui/main/MainActivity.kt:197-199` |
| 개발자 | 없음 | 개발 빌드 + aboutButton 활성 | `ui/main/MainActivity.kt:200-206` |
| 정보 | 없음 | aboutButton 활성 시 | `ui/main/MainActivity.kt:207-209` |
| 편집 | ic_baseline_edit_24 | ViewModel 관찰 | `ui/main/MainActivity.kt:214-218` |
| 삭제 | ic_baseline_delete_24 | ViewModel 리스너 존재 시 | `ui/main/MainActivity.kt:219-224` |

기본 전부 숨김이고 ViewModel 관찰로 개별 노출된다(`ui/main/MainActivity.kt:225-226`).

### 2.3 내비게이션 라우트 전체 (24개 목적지)

`ui/main/settings/SettingsRoute.kt:55-294`가 정본이다.

| 그룹 | 라우트 | 대상 Fragment | 근거 |
|---|---|---|---|
| Index | Index | MainFragment | `SettingsRoute.kt:60`, `214-216` |
| Fcitx | GlobalConfig | GlobalConfigFragment | `SettingsRoute.kt:65`, `220` |
| Fcitx | InputMethodList | InputMethodListFragment | `SettingsRoute.kt:68`, `221-223` |
| Fcitx | InputMethodConfig(name, uniqueName) | InputMethodConfigFragment | `SettingsRoute.kt:71`, `224` |
| Fcitx | AddonList | AddonListFragment | `SettingsRoute.kt:74`, `225-227` |
| Fcitx | AddonConfig(name, uniqueName) | AddonConfigFragment | `SettingsRoute.kt:77`, `228` |
| Android | Theme | ThemeFragment | `SettingsRoute.kt:82`, `232-234` |
| Android | VirtualKeyboard | KeyboardSettingsFragment | `SettingsRoute.kt:85`, `235-237` |
| Android | CandidatesWindow | CandidatesSettingsFragment | `SettingsRoute.kt:88`, `238-240` |
| Android | Clipboard | ClipboardSettingsFragment | `SettingsRoute.kt:91`, `241-243` |
| Android | Symbol | SymbolSettingsFragment | `SettingsRoute.kt:94`, `244-246` |
| Android | Plugin | PluginFragment | `SettingsRoute.kt:97`, `247-249` |
| Android | Advanced | AdvancedSettingsFragment | `SettingsRoute.kt:100`, `250-252` |
| Android | PrivacyAi | PrivacyAiSettingsFragment | `SettingsRoute.kt:103`, `253-255` |
| Android | AppProfiles | AppProfileSettingsFragment | `SettingsRoute.kt:106`, `256-258` |
| Android | PersonalDictionary | PersonalDictionaryFragment | `SettingsRoute.kt:109`, `259-261` |
| Android | Developer | DeveloperFragment | `SettingsRoute.kt:112`, `262-264` |
| Android | License | LicensesFragment | `SettingsRoute.kt:115`, `265-267` |
| Android | LegalDocument(kind) | LegalDocumentFragment | `SettingsRoute.kt:118`, `268-270` |
| Android | About | AboutFragment | `SettingsRoute.kt:121`, `271-273` |
| External | ListConfig(params) | ListFragment | `SettingsRoute.kt:126`, `277-279` |
| External | PinyinDict(uri) | PinyinDictionaryFragment | `SettingsRoute.kt:163`, `280-282` |
| External | Punctuation(title, lang) | PunctuationEditorFragment | `SettingsRoute.kt:168`, `283` |
| External | QuickPhraseList | QuickPhraseListFragment | `SettingsRoute.kt:171`, `284-286` |
| External | QuickPhraseEdit(param) | QuickPhraseEditFragment | `SettingsRoute.kt:174`, `287-289` |
| External | TableInputMethods | TableInputMethodFragment | `SettingsRoute.kt:203`, `290-292` |
| External | PinyinCustomPhrase | PinyinCustomPhraseFragment | `SettingsRoute.kt:206`, `293` |

`LegalDocumentKind`는 DataPrivacy, Notice, ForkNotice, Source 4종이다(`SettingsRoute.kt:47-52`).

### 2.4 AppPrefs 키 전체 (77개, 6개 카테고리)

#### Internal (UI 없음, 8개) — `data/prefs/AppPrefs.kt:33-43`

| 키 | 기본값 |
|---|---|
| `first_run` | true |
| `last_symbol_layout` | NumberKeyboard.Name |
| `last_picker_type` | PickerWindow.Key.Emoji.name |
| `verbose_log` | false |
| `pid` | 0 |
| `editor_info_inspector` | false |
| `need_notifications` | true |
| `automatic_ondevice_suggestions_opt_in` | true |

#### Advanced (10개) — `data/prefs/AppPrefs.kt:45-87`

| 키 | 기본값 | 비고 |
|---|---|---|
| `ignore_system_cursor` | false | - |
| `offline_mode` | false | 완전 오프라인 모드 |
| `graph_enrich_auto` | false | 자동 그래프 강화 |
| `auto_snippet_expansion` | true | - |
| `buffered_hangul_input` | false | 한글 버퍼 호환 모드 |
| `buffered_hangul_transport` | SystemPaste | bufferedHangulInput 켠 경우만 |
| `hide_key_config` | true | - |
| `disable_animation` | false | - |
| `vivo_keypress_workaround` | SDK<34 && Vivo OriginOS | 기기 감지 기본값 |
| `ignore_system_window_insets` | false | - |

#### Keyboard (42개) — `data/prefs/AppPrefs.kt:89-377`

| 키 | 기본값 | 범위/선택지 | 근거 |
|---|---|---|---|
| `mobile_hangul_layout` | Physical | 10종 | `:90-94` |
| `haptic_on_keypress` | FollowingSystem | 3모드 | `:95-100` |
| `haptic_on_keyup` | false | 햅틱 켠 경우만 | `:101-105` |
| `haptic_on_repeat` | false | - | `:106` |
| `button_vibration_press_milliseconds` | 0 | 0-100 ms | `:112-124` |
| `button_vibration_long_press_milliseconds` | 0 | 0-100 ms | `:112-124` |
| `button_vibration_press_amplitude` | 0 | 0-255 | `:133-149` |
| `button_vibration_long_press_amplitude` | 0 | 0-255 | `:133-149` |
| `sound_on_keypress` | FollowingSystem | 3모드 | `:154-158` |
| `button_sound_volume` | 0 | 0-100 % | `:159-169` |
| `reset_keyboard_on_focus_change` | true | - | `:170-171` |
| `expand_toolbar_by_default` | false | - | `:172-173` |
| `inline_suggestions` | true | - | `:174` |
| `show_number_row` | false | - | `:175-180` |
| `toolbar_num_row_on_password` | true | showNumberRow 꺼진 경우만 | `:182-186` |
| `popup_on_key_press` | true | - | `:187` |
| `keep_keyboard_letters_uppercase` | false | - | `:188-192` |
| `show_voice_input_button` | false | - | `:194-195` |
| `preferred_voice_input` | "" | showVoiceInputButton 켠 경우만 | `:196-198` |
| `expand_keypress_area` | false | - | `:200-201` |
| `swipe_symbol_behavior` | Down | Up/Down/Disabled | `:202-206` |
| `keyboard_long_press_delay` | 300 | 100-700 ms, step 10 | `:207-215` |
| `space_long_press_behavior` | None | 4종 | `:216-220` |
| `space_swipe_move_cursor` | true | - | `:221-222` |
| `show_lang_switch_key` | true | - | `:223-224` |
| `lang_switch_key_behavior` | Enumerate | 3종, showLangSwitchKey 켠 경우만 | `:225-229` |
| `keyboard_height_percent` | 30 | 10-90 % | `:235-248` |
| `keyboard_height_percent_landscape` | 49 | 10-90 % | `:235-248` |
| `keyboard_side_padding` | 0 | 0-300 dp | `:255-268` |
| `keyboard_side_padding_landscape` | 0 | 0-300 dp | `:255-268` |
| `keyboard_bottom_padding` | 0 | 0-100 dp | `:275-288` |
| `keyboard_bottom_padding_landscape` | 0 | 0-100 dp | `:275-288` |
| `split_keyboard_compact` | false | - | `:291-296` |
| `split_keyboard_expanded` | false | - | `:297-302` |
| `split_keyboard_compact_gap_portrait` | 48 | 24-180 dp, step 4 | `:308-322` |
| `split_keyboard_compact_gap_landscape` | 72 | 24-180 dp, step 4 | `:308-322` |
| `split_keyboard_expanded_gap_portrait` | 96 | 32-240 dp, step 4 | `:329-343` |
| `split_keyboard_expanded_gap_landscape` | 128 | 32-240 dp, step 4 | `:329-343` |
| `horizontal_candidate_style` | AutoFillWidth | 3종 | `:346-350` |
| `expanded_candidate_style` | Grid | Grid/Flexbox | `:352-356` |
| `expanded_candidate_grid_span_count_portrait` | 6 | 4-12 | `:362-374` |
| `expanded_candidate_grid_span_count_landscape` | 8 | 4-12 | `:362-374` |

#### Candidates (9개) — `data/prefs/AppPrefs.kt:379-438`

| 키 | 기본값 | 범위 |
|---|---|---|
| `show_candidates_window` | InputDevice | 3종 |
| `two_row_candidate_bar` | true | - |
| `candidates_window_orientation` | Automatic | 3종 |
| `candidates_window_min_width` | 0 | 0-640 dp, step 10 |
| `candidates_window_padding` | 4 | 0-32 dp |
| `candidates_window_font_size` | 20 | 4-64 sp |
| `candidates_window_radius` | 0 | 0-48 dp |
| `candidates_item_padding_vertical` | 2 | 0-64 dp |
| `candidates_item_padding_horizontal` | 4 | 0-64 dp |

#### Clipboard (6개) — `data/prefs/AppPrefs.kt:440-464`

| 키 | 기본값 | 비고 |
|---|---|---|
| `clipboard_enable` | false | 상위 토글 |
| `clipboard_limit` | 10 | clipboard_enable 켠 경우만 |
| `clipboard_suggestion` | true | clipboard_enable 켠 경우만 |
| `clipboard_item_timeout` | 30 | -1 ~ Int.MAX, 초 |
| `clipboard_return_after_paste` | false | clipboard_enable 켠 경우만 |
| `clipboard_mask_sensitive` | true | clipboard_enable 켠 경우만 |

#### Symbols (2개) — `data/prefs/AppPrefs.kt:466-478`

| 키 | 기본값 |
|---|---|
| `hide_unsupported_emojis` | true |
| `default_emoji_skin_tone` | EmojiModifier.SkinTone.Default |

관리형 설정 총합은 AppPrefs 77개 + ThemePrefs 16개로 93개다.

기기 암호화 저장소로 동기화하는 부분집합은 `data/prefs/AppPrefs.kt:510-535`에 정의돼 있다: internal 2개(verboseLog, editorInfoInspector), advanced 4개(ignoreSystemCursor, offlineMode, disableAnimation, vivoKeypressWorkaround), keyboard/candidates/clipboard 카테고리 전체.

### 2.5 열거형 선택지

| 설정 키 | 선택지 | 근거 |
|---|---|---|
| `mobile_hangul_layout` | Physical, Chunjiin, ChunjiinPlus, Danmoum, MoakeyOneHand, MoakeyTwoHand, Vega, VegaCenter, Naratgul, NaratgulCenter (10) | `input/keyboard/MobileHangulLayout.kt:10-20` |
| `haptic_on_keypress`, `sound_on_keypress` | FollowingSystem, Enabled, Disabled (3) | `data/InputFeedbacks.kt:23-27` |
| `space_long_press_behavior` | None, Enumerate, ToggleActivate, ShowPicker (4) | `input/keyboard/SpaceLongPressBehavior.kt:10-14` |
| `lang_switch_key_behavior` | Enumerate, ToggleActivate, NextInputMethodApp (3) | `input/keyboard/LangSwitchBehavior.kt:10-13` |
| `swipe_symbol_behavior` | Up, Down, Disabled (3) | `input/keyboard/SwipeSymbolDirection.kt:10-13` |
| `horizontal_candidate_style` | NeverFillWidth, AutoFillWidth, AlwaysFillWidth (3) | `input/candidates/horizontal/HorizontalCandidateMode.kt:11-14` |
| `expanded_candidate_style` | Grid, Flexbox (2) | `input/candidates/expanded/ExpandedCandidateStyle.kt:10-12` |
| `show_candidates_window` | SystemDefault, InputDevice, Disabled (3) | `input/candidates/floating/FloatingCandidatesMode.kt:11-14` |
| `candidates_window_orientation` | Automatic, Horizontal, Vertical (3) | `input/candidates/floating/FloatingCandidatesOrientation.kt:11-14` |
| `buffered_hangul_transport` | SystemPaste, CtrlV, DirectCommit (3) | `input/BufferedInputTransport.kt:10-13` |
| `punctuation_position` | None, Bottom, TopRight (3) | `data/theme/ThemePrefs.kt:94-98` |
| `navbar_background` | None, ColorOnly, Full (3) | `data/theme/ThemePrefs.kt:106-110` |
| `default_emoji_skin_tone` | EmojiModifier.SkinTone | `input/popup/EmojiModifier.kt` |

선택지 합계 43개(default_emoji_skin_tone 제외 기준)이며, 사운드 효과 4종(`data/InputFeedbacks.kt:107-109`)과 앱 프로필 정책 열거(Inherit/Allow/Block, Inherit/Expanded/Collapsed)를 더하면 57개다.

### 2.6 설정 위젯 타입

`data/prefs/ManagedPreferenceUi.kt`가 제공하는 UI 타입은 6종이다.

| 타입 | 근거 | 대응 위젯 |
|---|---|---|
| Switch | `data/prefs/ManagedPreferenceUi.kt:29-47` | MySwitchPreference |
| StringList (enum 포함) | `data/prefs/ManagedPreferenceUi.kt:49-71` | ListPreference |
| VoiceInputList | `data/prefs/ManagedPreferenceUi.kt:73-90` | ListPreference (설치된 음성 IME 열거) |
| SeekBarInt | `data/prefs/ManagedPreferenceCategory.kt:101-103` | DialogSeekBarPreference |
| EditTextInt | `data/prefs/ManagedPreferenceCategory.kt:97-99` | EditTextIntPreference (범위/step >= 240일 때 자동 선택) |
| TwinSeekBarInt | `data/prefs/ManagedPreferenceCategory.kt:136-141` | TwinSeekBarPreference (세로/가로 등 2값 1행) |

`twinInt` 쌍은 총 11쌍이다. 테마 2쌍(key_horizontal_margin, key_vertical_margin), 앱 9쌍(button_vibration_milliseconds, button_vibration_amplitude, keyboard_height, keyboard_side_padding, keyboard_bottom_padding, split_keyboard_compact_gap, split_keyboard_expanded_gap, expanded_candidate_grid_span_count, candidates_padding).

`enableUiOn` 조건부 노출은 약 15곳이다.

### 2.7 온보딩 마법사

| 단계 | 완료 조건 | 필수 | 근거 |
|---|---|---|---|
| Enable (입력기 켜기) | `InputMethodUtil.isEnabled()` | O | `ui/setup/SetupPage.kt:14`, `41-49` |
| Select (기본 입력기 선택) | `InputMethodUtil.isSelected()` | O | `ui/setup/SetupPage.kt:14`, `43` |
| AiSuggestion (Gemma 모델 준비) | `GemmaPreparationFactory.create(context)?.isModelReady()` | X | `ui/setup/SetupPage.kt:14`, `44`, `52-54` |

- AiSuggestion 페이지는 `GemmaPreparationFactory.create(context)`가 null이 아닐 때만 노출된다(`ui/setup/SetupPage.kt:52-54`).
- 버튼 4종: 건너뛰기, 이전, 다음(마지막은 완료) (`ui/setup/SetupActivity.kt:51-77`).
- ViewPager2 기반, 첫 페이지에서 이전 버튼 숨김(`ui/setup/SetupActivity.kt:74`).
- AiSuggestion 페이지에 온디바이스 제안 옵트인 스위치(`ui/setup/SetupFragment.kt:49-59`).
- 디버그 빌드에서만 Gemma 안내 배너가 보이고 탭하면 대시보드로 이동(`ui/setup/SetupFragment.kt:35-43`).
- 온보딩 문구 3종은 `res/values/saegeul_ui.xml:18-20`에 있다: `saegeul_setup_title`, `saegeul_setup_summary`, `saegeul_setup_gemma_notice`.

### 2.8 고급 설정 화면

`ui/main/settings/behavior/AdvancedSettingsFragment.kt`는 `AppPrefs.advanced` 10개 키를 자동 렌더링하고, 아래 3개를 추가한다(`:99-137`).

| 항목 | 동작 | 근거 |
|---|---|---|
| 사용자 데이터 폴더 열기 | DocumentsProvider 인텐트 (길게 누르면 기본 저장소) | `:101-117` |
| 사용자 데이터 내보내기 | `fcitx5-android_<ISO8601>.zip` 생성 | `:118-126` |
| 사용자 데이터 가져오기 | 확인 다이얼로그 후 fcitx 정지 → 덮어쓰기 → 앱 재시작 | `:127-137`, `:48-80` |

### 2.9 개인정보·AI 설정 화면 (7개 카테고리)

`ui/main/settings/behavior/PrivacyAiSettingsFragment.kt` 1,545줄.

| 카테고리 | 항목 | 근거 |
|---|---|---|
| 언어 금고 (디버그 전용) | 자동 온디바이스 제안 스위치(`automatic_ondevice_suggestions_opt_in`), 대시보드 진입 | `:112-132` |
| 네트워크 제어 | 완전 오프라인 모드(`privacy_offline_mode`, 비영속 미러), 문장 팩 | `:133-165` |
| 외부 글쓰기 AI | 공급자 설정(모드 다이얼로그), 커스텀 공급자 삭제 | `:166-185` |
| 음성 공급자 | 모드(기기 받아쓰기 / OpenAI Realtime / OpenAI API), API 키 설정, 키 삭제 | `:186-214` |
| GIF 공급자 | 선택(Standard/Commons/Giphy), Klipy 설정, Klipy 키 삭제, Giphy 설정, Giphy 키 삭제 | `:215-261` |
| AI 언어 지문 (Typing DNA) | 리포트, 대시보드(릴리스만), 즉시 동기화, 전체 초기화(Zero-Knowledge) | `:262-314` |
| 로컬 데이터 / 개인정보 보증 | 사용량 표시, 사용량 삭제, GIF 캐시 삭제, 보증 문구 | `:316-336` |

오프라인 모드를 켜면 문장 팩 다운로드가 즉시 취소된다(`:141-143`). 언어 지문 초기화는 typingDnaRepository, typingDnaVault, personalNgramModel, personalSentenceVault 4개를 모두 비운다(`:300-305`).

AI 인증 모드는 API Key / OAuth 2종이고, OAuth는 authorization·token·revocation 엔드포인트와 client_id·scopes를 직접 입력하는 고급 경로가 있다(`:1193-1231`).

---

## 3. 호스트 앱 화면

### 3.1 Activity 목록 (13개)

| Activity | 라벨 | 테마 | 근거 |
|---|---|---|---|
| MainActivity | app_name | Theme.FcitxAppTheme | `AndroidManifest.xml:103-119` |
| SetupActivity | - | - | `AndroidManifest.xml:74-77` |
| TypingDnaDashboardActivity | AI 언어 지문 대시보드 | Theme.TypingDnaDashboard | `AndroidManifest.xml:198-203` |
| CustomThemeActivity | edit_theme | - | `AndroidManifest.xml:84-88` |
| CropImageActivity | - | - | `AndroidManifest.xml:89-91` |
| LogActivity | - | - | `AndroidManifest.xml:78-83` |
| ClipboardEditActivity | edit_clipboard | Theme.DialogTheme | `AndroidManifest.xml:148-156` |
| AiProviderSetupActivity | ai_setup_wizard_title | - | `AndroidManifest.xml:206-209` |
| AiOAuthLoginActivity | - | Theme.OAuthDialog | `AndroidManifest.xml:190-196` |
| AiShareReceiverActivity | ai_share_target | NoDisplay | `AndroidManifest.xml:215-228` |
| VoicePermissionActivity | - | Theme.VoicePermission | `AndroidManifest.xml:164-170` |
| SensitivePhraseAuthActivity | - | Theme.VoicePermission | `AndroidManifest.xml:173-179` |
| OcrDocumentActivity | - | Theme.VoicePermission | `AndroidManifest.xml:182-187` |

그 외 Service 2개(FcitxInputMethodService, FcitxRemoteService), Provider 2개(FcitxDataProvider, FileProvider), Receiver 1개(VaultWidgetProvider)가 있다.

권한 6종: VIBRATE, POST_NOTIFICATIONS, INTERNET, AD_ID, RECORD_AUDIO, USE_BIOMETRIC (`AndroidManifest.xml:14-19`).

### 3.2 Typing DNA 대시보드 카드 구성 (12개 영역)

`res/layout/activity_typing_dna_dashboard.xml` 1,195줄.

| 영역 | 근거 (layout 줄) | 내용 |
|---|---|---|
| 동기화 필 / 진행 / 오류 | `:32`, `:67`, `:92` | 툴바 우측 배지, 진행 메시지, 실패 메시지 |
| 레벨 요약 카드 | `:101-210` | 레벨 배지, 설명, 진행 바, 다음 레벨까지 문장 수, 습관 줄, 포인트 줄 |
| 외부 LLM 강화 카드 | `:222-320` | 동기화 레벨, 그래프 통계, 가용성, 설정 버튼, 지금 동기화 버튼 |
| 보안 카드 | `:325-398` | StrongBox / TEE / 소프트웨어 3단계 (`ui/main/ai/TypingDnaDashboardActivity.kt:773-775`) |
| 무결성 카드 | `:401-560` | 수락률, 개인화 적중률, 절약 키 입력, 교정 오타 수 |
| 성장 타임라인 카드 | `:567-625` | VaultTimelineView (172줄) |
| 자주 쓰는 단어 카드 | `:631-665` | 칩 그룹 |
| 카테고리 분포 카드 | `:668-838` | 메신저 / 업무 / 일반 3분류 막대 |
| 지표 타일 4개 | `:840-1000` | 분석 문장, 단어쌍, 종결어미, 상용구 |
| N-gram / RAG / 마지막 학습 | `:1002-1033` | 텍스트 3줄 |
| 차트 카드 | `:1035-1058` | TypingDnaChartView (452줄) |
| 개인정보 보증 카드 + 전체 초기화 | `:1061-1128` | PII 스크러빙·Zero-Knowledge 문구 + 초기화 버튼 |

로딩 오버레이(진행, 실패, 재시도)는 `:1131-1175`에 있다.

Gemma 언어 금고 카드는 별도 레이아웃 `res/layout/view_gemma_vault_preparation.xml`로 붙으며 항목은 16개다: 제목, 설명, 사적 경계 고지, 저장 개수, 마지막 실행, 상태, 오류, 자동 준비 스위치, 자동 안내, 가드 안내, 생성 버튼, 개인 동기화 결과, 수동 설명, 중지 버튼, 중지 안내, 모델 정보.

레벨업 축하 오버레이는 `ui/main/ai/CelebrationOverlayView.kt` (124줄), 대기 확인은 `TypingDnaDashboardActivity.kt:337-353`.

### 3.3 홈 화면 위젯

| 항목 | 값 | 근거 |
|---|---|---|
| 최소 크기 | 180x110dp | `res/xml/vault_widget_info.xml` |
| 셀 | 3x2 | `res/xml/vault_widget_info.xml` |
| 갱신 주기 | 1,800,000ms (30분) | `res/xml/vault_widget_info.xml` |
| 리사이즈 | horizontal\|vertical | `res/xml/vault_widget_info.xml` |
| 라벨 | 내 언어 금고 | `AndroidManifest.xml:95` |
| 표시 항목 | 제목, 마지막 동기화, 레벨, 진행 바, 분석 문장 수 | `res/layout/widget_vault.xml:24-60` |
| 레벨업 대기 시 발광 배경 | `bg_widget_card_glow` | `ui/main/ai/VaultWidgetProvider.kt:63-68` |
| 탭 시 대시보드 열기 | PendingIntent | `ui/main/ai/VaultWidgetProvider.kt:27-55` |

### 3.4 홈 카드 프리퍼런스 (Typing DNA 카드)

`ui/main/ai/TypingDnaCardPreference.kt` 285줄, 레이아웃 `res/layout/view_typing_dna_card_preference.xml`.

표시 항목 7개: 레벨 배지, 제목, 요약, 진행 바, 미니 차트(TypingDnaChartView), 지표 4개(문장/단어쌍/어미/상용구), 상세 그래프 보기 버튼 (`TypingDnaCardPreference.kt:141-152`).

로드 실패·로딩 중 상태를 별도 문자열로 처리한다(`:192-221`).

### 3.5 자판 미리보기

`ui/main/settings/theme/KeyboardPreviewUi.kt:43-193`. 실제 `TextKeyboard`를 생성해 테마를 입히고 화면비를 계산한다(`:128-146`). 테마 편집기와 테마 탭 상단 모두에서 재사용한다.

### 3.6 사전 관리·백업·법적 문서

| 화면 | 근거 | 내용 |
|---|---|---|
| 개인 사전 | `ui/main/settings/behavior/PersonalDictionaryFragment.kt:42-87` | 활성 스위치, 단어 추가, 단어 목록, 개인정보 안내 |
| 개인 사전 분류 3종 | `ui/main/settings/behavior/PersonalDictionaryFragment.kt:153-159` | Name, Company, TechnicalTerm |
| 앱 프로필 | `ui/main/settings/behavior/AppProfileSettingsFragment.kt:44-95` | 패키지별 레이아웃·테마·툴바·전송방식·네트워크·AI 정책 |
| 앱 프로필 툴바 정책 | `ui/main/settings/behavior/AppProfileSettingsFragment.kt:243-245` | Inherit, Expanded, Collapsed |
| 앱 프로필 기능 정책 | `ui/main/settings/behavior/AppProfileSettingsFragment.kt:251-253` | Inherit, Allow, Block |
| 민감 상용구 금고 | `ui/main/settings/SensitivePhraseVaultSettings.kt:32-60` | 생체 인증 후 열람 |
| 사용자 데이터 백업·복원 | `ui/main/settings/behavior/AdvancedSettingsFragment.kt:101-137` | ZIP 내보내기·가져오기 |
| 정보 화면 | `ui/main/AboutFragment.kt:23-64` | 데이터 개인정보 고지, 개인정보 처리방침 링크, 오픈소스 라이선스, 포크 고지, NOTICE, 소스 공개, 라이선스 SPDX, 버전·배포 채널·git 해시·빌드 시각 |
| 라이선스 | `ui/main/LicensesFragment.kt:22-60` | aboutlibraries JSON 기반, 라이선스 전문 + 라이브러리별 목록 |
| 법적 문서 | `ui/main/LegalDocumentFragment.kt:33-38` | assets의 `legal/DATA-PRIVACY.txt`, `NOTICE.txt`, `FORK-NOTICE.txt` + 소스 공개 고지 |
| 소스 공개 고지 | `ui/main/LegalDocumentFragment.kt:61-76` | BuildConfig의 SOURCE_REPOSITORY_URL, SOURCE_ARCHIVE_URL, SOURCE_TAG, BUILD_GIT_HASH |
| 개발자 도구 | `ui/main/DeveloperFragment.kt:65-142` | 실시간 로그, verbose 로그, EditorInfo 인스펙터, fcitx 재시작, 데이터 삭제·동기화, 클립보드 DB 비우기, 힙 덤프 |
| 플러그인 | `ui/main/PluginFragment.kt:104-175` | 로드됨 / 실패 목록 |
| 플러그인 실패 사유 6종 | `ui/main/PluginFragment.kt:145-164` | invalid_data_descriptor, missing_data_descriptor, missing_plugin_descriptor, path_conflict, incompatible_api, invalid_plugin_descriptor |

### 3.7 백업 제외 항목 (16개)

`res/xml/full_backup_content.xml`과 `res/xml/data_extraction_rules.xml`에 동일하게 선언돼 있다. cloud-backup과 device-transfer 양쪽에서 제외된다.

| 도메인 | 경로 |
|---|---|
| file | usr, descriptor.json, licenses.json, README.md |
| file | typing_dna.json, typing_dna_pending.json |
| file | personalized_sentences.json, personal_ngram.json, personal_corrections.json, personal_rag.json, personal_graph.json |
| database | clbdb, clbdb-shm, clbdb-wal, clbdb-journal |

---

## 4. 브랜드·디자인 토큰

### 4.1 앱 셸 토큰

| 토큰 | 라이트 | 다크 | 근거 |
|---|---|---|---|
| saegeul_canvas | #FFF9ED | #101827 | `res/values/saegeul_ui.xml:3`, `res/values-night/saegeul_ui.xml:3` |
| saegeul_surface | #FFFFFF | #192536 | `:4` / `:4` |
| saegeul_ink | #101827 | #FFF9ED | `:5` / `:5` |
| saegeul_secondary | #52605D | #B8C8C2 | `:6` / `:6` |
| saegeul_action | #176B50 | #55D6A6 | `:7` / `:7` |
| saegeul_on_action | #FFFFFF | #101827 | `:8` / `:8` |
| saegeul_subtle | #EAF4EE | #20392F | `:9` / `:9` |
| saegeul_outline | #D2DED7 | #43534F | `:10` / `:10` |
| widget_glow_color | #FF2E8B6A | #FF55D6A6 | `:11` / `:11` |

치수 토큰 4개 (`res/values/saegeul_ui.xml:13-16`):

| 토큰 | 값 |
|---|---|
| saegeul_screen_inset | 24dp |
| saegeul_card_radius | 20dp |
| saegeul_button_radius | 12dp |
| saegeul_touch_target | 48dp |

범용 색 8개 (`res/values/colors.xml`): red_A700 #D50000, red_400 #EF5350, yellow_500 #FFEB3B, yellow_800 #F9A825, blue_500 #2196F3, grey_400 #BDBDBD, grey_700 #616161, card_stroke_color #1F000000 (다크 #26FFFFFF, `res/values-night/colors.xml`).

### 4.2 브랜드 팔레트

`docs/brand/README.md`.

| 명칭 | HEX | RGB | 용도 | 시각적 의미 |
|---|---|---|---|---|
| Ink Navy | #101827 | rgb(16,24,39) | 기본 배경, 어두운 표면 | 독립 제품의 안정감 |
| Warm Ivory | #FFF9ED | rgb(255,249,237) | 자모 획, 밝은 텍스트 | 한글 조형, 종이 질감 |
| Fresh Jade | #55D6A6 | rgb(85,214,166) | 텍스트 커서, 액센트 | 지능형 어시스턴트 |
| Jade Bright | #79F1C2 | rgb(121,241,194) | 인터랙티브 하이라이트 | 버튼 호버, 발광 글래스 |

`Jade Bright`는 브랜드 문서에만 있고 `saegeul_ui.xml`에는 토큰이 없다. iOS 이식 시 이 색은 코드 토큰이 아니라 디자인 가이드 값으로 다뤄야 한다.

브랜드 규정: Fcitx 로고, 펭귄 심볼, Android 로봇, 기존 Fcitx5 앱 자산을 일절 사용하지 않는다. SVG 원본은 CC-BY-4.0, 앱 패키지 내 파생 리소스는 LGPL-2.1-or-later.

### 4.3 앱 테마·스타일 (7개)

`res/values/themes.xml`.

| 스타일 | 부모 | 용도 |
|---|---|---|
| Theme.FcitxAppTheme | Theme.MaterialComponents.DayNight.NoActionBar | 호스트 앱 전역 |
| Theme.InputViewTheme | @android:style/Theme.DeviceDefault.Settings | 입력 뷰 |
| Theme.DialogTheme | @android:style/Theme.DeviceDefault.Light.Dialog | 클립보드 편집 |
| Theme.OAuthDialog | Theme.AppCompat.Light.Dialog.Alert | AI OAuth 로그인 |
| Theme.VoicePermission | @android:style/Theme.Translucent.NoTitleBar | 음성·생체·OCR 권한 |
| Theme.TypingDnaDashboard | Theme.MaterialComponents.DayNight.NoActionBar | 대시보드 |
| InputViewSnackbarTheme | Theme.MaterialComponents.Light.NoActionBar | 입력 뷰 스낵바 |

`Theme.FcitxAppTheme`는 saegeul 토큰을 windowBackground, statusBarColor, navigationBarColor, textColorPrimary/Secondary, colorPrimary/OnPrimary, colorSecondary/OnSecondary, colorSurface/OnSurface, colorControlNormal에 매핑한다.

로그 뷰어용 커스텀 속성 6개 (`res/values/attrs.xml:3-8`): colorLogFatal, colorLogError, colorLogWarning, colorLogInfo, colorLogDebug, colorLogVerbose.

DialogSeekBarPreference 속성 5개, FcitxKeyPreference 속성 1개도 같은 파일에 있다(`res/values/attrs.xml:12-22`).

### 4.4 아이콘 자산

| 항목 | 경로 |
|---|---|
| 브랜드 SVG 원본 (CC-BY-4.0) | `docs/brand/saegeul-icon.svg` |
| 마스터 PNG 1024x1024 | `docs/brand/saegeul-icon-master.png` |
| 적응형 아이콘 (배경+전경+monochrome) | `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` |
| 원형 적응형 아이콘 | `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` |
| 디버그 변형 | `ic_launcher_debug.xml`, `ic_launcher_round_debug.xml` |
| 래스터 밀도 5단계 | `mipmap-{h,m,xh,xxh,xxxh}dpi/` 각 4파일 |
| 벡터 드로어블 | `app/src/main/res/drawable/` 139개 |
| 배경 셰이프 드로어블 | bg_banner_slot, bg_dna_level_badge, bg_dna_metric_chip, bg_sync_pill, bg_vault_level_badge, bg_widget_card, bg_widget_card_glow, bkg_inline_suggestion_dark/light, bkg_theme_choose_image |

앱 아이콘은 다크 테마용 monochrome 레이어까지 갖췄다. 번들 폰트는 없고 모두 시스템 `Typeface`를 쓴다(`Typeface.DEFAULT_BOLD`, `Typeface.defaultFromStyle` 등).

레이아웃 XML은 10개뿐이다: activity_clipboard_edit, activity_log, activity_main, activity_setup, activity_typing_dna_dashboard, fragment_setup, view_gemma_preparation_preference, view_gemma_vault_preparation, view_typing_dna_card_preference, widget_vault. 나머지 UI는 전부 splitties View DSL 코드다.

---

## 5. 접근성·국제화

### 5.1 지원 언어 (8개)

| 로케일 | strings 개수 | 비고 |
|---|---|---|
| 기본 (en-US) | 1,219 (strings.xml 1,095 + 별도 파일) | `res/resources.properties`의 `unqualifiedResLocale=en-US` |
| ko | 1,134 (strings.xml 1,092) | 미번역 3개: `vault_widget_add`, `vault_widget_prompt`, `vault_widget_title` |
| zh-rCN | 317 | strings.xml만 |
| zh-rTW | 316 | strings.xml만 |
| ru | 316 | strings.xml만 |
| ja | 254 | strings.xml만 |
| de | 161 | strings.xml만 |
| es | 124 | strings.xml만 |

기본 리소스는 `res/values/`에 11개 파일로 쪼개져 있다: strings.xml, ai_connection_status.xml, gemma_automatic_strings.xml, gemma_context_strings.xml, gemma_home_strings.xml, gemma_vault_strings.xml, graph_enrichment_failure.xml, sentence_packs.xml, strings_privacy_ai_async.xml, typing_dna_card_state.xml, saegeul_ui.xml.

한국어만 이 분할을 따라 6개 파일(ai_connection_status, graph_enrichment_failure, sentence_packs, strings, strings_privacy_ai_async, typing_dna_card_state)을 번역했다. 다른 언어는 strings.xml만 번역돼 있다.

plurals는 기본과 ko에 각각 1개씩 있다. string-array는 없다.

앱 이름은 strings.xml이 아니라 빌드 스크립트에서 주입한다: `app/build.gradle.kts:239`(release → `@string/app_name_release`), `:254`(debug → `@string/app_name_debug`).

한국어 문자열 다수가 코드에 하드코딩돼 있다. 예: `ui/main/settings/behavior/PrivacyAiSettingsFragment.kt:112`("언어 금고"), `:166`("외부 글쓰기 AI"), `:262`("AI 언어 지문 (Typing DNA)"), `ui/main/settings/theme/ThemeListFragment.kt:239,254,263,268-276`, `ui/main/ai/TypingDnaCardPreference.kt:239,247,252,272-275`, `res/layout/activity_typing_dna_dashboard.xml:878,916,954,992,1093,1104,1121`, `AndroidManifest.xml:95,201`. iOS 이식 시 이 문자열들은 리소스화 대상이다.

### 5.2 접근성

| 항목 | 수치 | 근거 |
|---|---|---|
| Kotlin `contentDescription` 지정 | 88곳 | AI 어시스턴트 UI, 온디바이스 완성 창, 툴바 버튼, 후보 창 등 |
| XML `android:contentDescription` | 13곳 | `app/src/main/res/layout/` |
| 최소 터치 타깃 토큰 | 48dp | `res/values/saegeul_ui.xml:16` |
| 레벨 배지 음성 안내 | `vault_level_content_description` | `ui/main/ai/TypingDnaDashboardActivity.kt:700`, `ui/main/ai/TypingDnaCardPreference.kt:239` |
| 후보 확장 버튼 상태 안내 | expand/hide 토글 | `input/bar/KawaiiBarComponent.kt:601,610` |
| 툴바 확장 버튼 안내 | - | `input/bar/ui/idle/ButtonsBarUi.kt:104,129,139,149` |
| 선택 가능한 법적 문서 텍스트 + 링크화 | `setTextIsSelectable(true)`, LinkifyCompat | `ui/main/LegalDocumentFragment.kt:46-48` |

### 5.3 햅틱·사운드

| 설정 | 키 | 기본값 | 범위 | 근거 |
|---|---|---|---|---|
| 키 누름 햅틱 | `haptic_on_keypress` | FollowingSystem | 3모드 | `data/prefs/AppPrefs.kt:95-100` |
| 키 뗌 햅틱 | `haptic_on_keyup` | false | 햅틱 켠 경우만 | `data/prefs/AppPrefs.kt:101-105` |
| 반복 입력 햅틱 | `haptic_on_repeat` | false | - | `data/prefs/AppPrefs.kt:106` |
| 진동 지속시간 (누름) | `button_vibration_press_milliseconds` | 0 = 시스템 기본 | 0-100 ms | `data/prefs/AppPrefs.kt:112-124` |
| 진동 지속시간 (길게) | `button_vibration_long_press_milliseconds` | 0 | 0-100 ms | `data/prefs/AppPrefs.kt:112-124` |
| 진동 세기 (누름) | `button_vibration_press_amplitude` | 0 | 0-255 | `data/prefs/AppPrefs.kt:133-149` |
| 진동 세기 (길게) | `button_vibration_long_press_amplitude` | 0 | 0-255 | `data/prefs/AppPrefs.kt:133-149` |
| 키 사운드 | `sound_on_keypress` | FollowingSystem | 3모드 | `data/prefs/AppPrefs.kt:154-158` |
| 사운드 볼륨 | `button_sound_volume` | 0 = 시스템 기본 | 0-100 % | `data/prefs/AppPrefs.kt:159-169` |

- 진동 세기 UI는 지속시간이 0이 아니고 기기가 진폭 제어를 지원할 때만 노출된다(`data/prefs/AppPrefs.kt:144-149`).
- 사운드 효과 4종: Standard, SpaceBar, Delete, Return (`data/InputFeedbacks.kt:107-109`).
- 햅틱 상수 3종: LONG_PRESS, KEYBOARD_RELEASE(SDK 27+ keyUp), KEYBOARD_TAP (`data/InputFeedbacks.kt:69-77`).
- 시스템 햅틱이 꺼진 Android 13에서도 `FLAG_IGNORE_GLOBAL_SETTING`으로 동작하는 우회 경로가 있다(`data/InputFeedbacks.kt:96-103`).
- 시스템 설정 동기화: `Settings.System.SOUND_EFFECTS_ENABLED`, `Settings.System.HAPTIC_FEEDBACK_ENABLED` (`data/InputFeedbacks.kt:32-38`).

---

## 6. 기능 총수

| 영역 | 항목 수 |
|---|---|
| 테마 색상 슬롯 (필수) | 22 |
| 테마 확장 구조체 / 필드 | 5 구조체 / 24 필드 |
| 내장 테마 (사용자 노출) | 15 (정의만 된 것 2개 별도) |
| 상점 테마 | 4 |
| Monet 동적 테마 | 2 (SDK 3단계 분기) |
| 테마 JSON 마이그레이션 단계 | 4 |
| 테마 편집기 섹션 | 10 |
| 편집기 효과 옵션 (앰비언트 13 + 방향 6 + 리액티브 5 + 파티클 5) | 29 |
| 편집기 프리셋·스와치 (프리셋 14 + 액센트 13 + 서피스 10 + 키색 9) | 46 |
| 키별 커스텀 대상 | 5 |
| 편집기 슬라이더 | 8 |
| ThemePrefs 설정 키 | 16 |
| AppPrefs 설정 키 | 77 |
| 관리형 설정 합계 | 93 |
| 열거형 설정 선택지 합계 | 57 |
| 설정 위젯 타입 | 6 |
| twinInt (세로/가로) 쌍 | 11 |
| enableUiOn 조건부 노출 | 약 15 |
| 최상위 설정 항목 | 13 (릴리스 11) |
| 내비게이션 목적지 | 24 |
| 개인정보·AI 설정 카테고리 | 7 |
| 온보딩 단계 | 3 |
| Activity | 13 |
| Service / Provider / Receiver | 2 / 2 / 1 |
| 대시보드 영역 | 12 |
| Gemma 금고 카드 항목 | 16 |
| 홈 위젯 | 1 |
| 홈 카드 표시 항목 | 7 |
| 브랜드·셸 색 토큰 (라이트/다크 쌍) | 9 |
| 치수 토큰 | 4 |
| 범용 색 | 8 |
| 앱 스타일 | 7 |
| 로그 색 속성 | 6 |
| 벡터 드로어블 | 139 |
| 레이아웃 XML | 10 |
| 백업 제외 항목 | 16 |
| 지원 로케일 | 8 |
| 기본 문자열 리소스 | 1,219 |
| Kotlin contentDescription | 88 |
| XML contentDescription | 13 |

**합계 기준 기능 항목: 약 460개.** 내역은 설정 키 93, 열거 선택지 57, 테마 색 슬롯·효과 필드 46, 테마 46종(내장 15 + 상점 4 + Monet 2 + 프리셋/스와치 25), 편집기 옵션 75, 화면·라우트 50, 토큰·스타일 34, 나머지 구성 요소 59다. iOS 1:1 격차표의 기준선으로 쓰기에 충분한 단위까지 분해했다.

---

## 7. 이식 시 주의할 구조적 사실

1. **설정 화면에 XML 정의가 없다.** 전부 `onCreatePreferences`에서 코드로 만들고, `ManagedPreferenceCategory`가 키 등록과 UI 생성을 동시에 한다(`data/prefs/ManagedPreferenceCategory.kt:16-155`). iOS에서는 이 선언·UI 결합 패턴을 옮길지 분리할지 결정이 필요하다.

2. **`enableUiOn` 조건부 노출이 광범위하다.** 상위 설정이 꺼지면 하위 설정 UI가 비활성화되는 의존 관계가 약 15곳 있다. 단순 키 매핑만으로는 재현되지 않는다.

3. **가로/세로 쌍(twinInt) 설정이 11쌍이다.** 하나의 UI 행에 두 값을 넣는 `TwinSeekBarPreference` 패턴이라 iOS에서는 별도 설계가 필요하다.

4. **테마 JSON은 버전 필드로 마이그레이션한다.** 직렬화 시 `version: "3.0"`을 주입하고 역직렬화 후 제거한다. 테마 파일 호환성을 유지하려면 이 규약을 그대로 따라야 한다. 알 수 없는 버전은 error로 처리된다.

5. **상점 테마 소유권은 기기 로컬이고 백업·이전 대상이 아니다.** iOS에서 같은 정책을 쓸지가 제품 결정 사항이다.

6. **정의만 되고 노출되지 않는 테마가 2개 있다** (TransparentDark, TransparentLight). 격차표에서 제외한다.

7. **한국어 문자열 다수가 코드·레이아웃에 하드코딩돼 있다.** iOS 이식 전에 리소스화 범위를 정해야 한다.

8. **`ProductSurfacePolicy` 하나가 3개 개발자 표면을 통제한다.** 릴리스 빌드 기준 최상위 항목은 11개이고, 플러그인 관리자·원시 엔진 설정·개발자 도구는 존재하지 않는 것으로 간주해야 한다.

9. **`Theme.Monet`은 Android 전용 개념이다.** SDK 34+ 실제 Monet, 31-33 근사값, 30 이하 정적 팔레트 3단계 분기가 있다. iOS에는 대응 개념이 없으므로 격차표에서 별도 표기가 필요하다.

10. **번들 폰트가 없다.** 전부 시스템 Typeface에 의존하므로 타이포그래피 이식은 플랫폼 기본 서체 매핑 문제가 된다.

---

## 8. 주요 파일 절대 경로

- `D:\workspace\Saegul\app\src\main\java\org\fcitx\fcitx5\android\data\theme\Theme.kt`
- `D:\workspace\Saegul\app\src\main\java\org\fcitx\fcitx5\android\data\theme\ThemePreset.kt`
- `D:\workspace\Saegul\app\src\main\java\org\fcitx\fcitx5\android\data\theme\ThemeManager.kt`
- `D:\workspace\Saegul\app\src\main\java\org\fcitx\fcitx5\android\data\theme\ThemePrefs.kt`
- `D:\workspace\Saegul\app\src\main\java\org\fcitx\fcitx5\android\data\theme\CustomThemeSerializer.kt`
- `D:\workspace\Saegul\app\src\main\java\org\fcitx\fcitx5\android\data\theme\ThemeFilesManager.kt`
- `D:\workspace\Saegul\app\src\main\java\org\fcitx\fcitx5\android\data\theme\ThemeMonet.kt`
- `D:\workspace\Saegul\app\src\main\java\org\fcitx\fcitx5\android\data\theme\ThemeShopCatalog.kt`
- `D:\workspace\Saegul\app\src\main\java\org\fcitx\fcitx5\android\data\theme\ThemeOwnershipStore.kt`
- `D:\workspace\Saegul\app\src\main\java\org\fcitx\fcitx5\android\data\prefs\AppPrefs.kt`
- `D:\workspace\Saegul\app\src\main\java\org\fcitx\fcitx5\android\data\prefs\ManagedPreferenceCategory.kt`
- `D:\workspace\Saegul\app\src\main\java\org\fcitx\fcitx5\android\data\prefs\ManagedPreferenceUi.kt`
- `D:\workspace\Saegul\app\src\main\java\org\fcitx\fcitx5\android\data\InputFeedbacks.kt`
- `D:\workspace\Saegul\app\src\main\java\org\fcitx\fcitx5\android\ui\main\MainActivity.kt`
- `D:\workspace\Saegul\app\src\main\java\org\fcitx\fcitx5\android\ui\main\MainFragment.kt`
- `D:\workspace\Saegul\app\src\main\java\org\fcitx\fcitx5\android\ui\main\ProductSurfacePolicy.kt`
- `D:\workspace\Saegul\app\src\main\java\org\fcitx\fcitx5\android\ui\main\AboutFragment.kt`
- `D:\workspace\Saegul\app\src\main\java\org\fcitx\fcitx5\android\ui\main\settings\SettingsRoute.kt`
- `D:\workspace\Saegul\app\src\main\java\org\fcitx\fcitx5\android\ui\main\settings\theme\CustomThemeActivity.kt`
- `D:\workspace\Saegul\app\src\main\java\org\fcitx\fcitx5\android\ui\main\settings\theme\ThemeFragment.kt`
- `D:\workspace\Saegul\app\src\main\java\org\fcitx\fcitx5\android\ui\main\settings\theme\ThemeListFragment.kt`
- `D:\workspace\Saegul\app\src\main\java\org\fcitx\fcitx5\android\ui\main\settings\theme\KeyboardPreviewUi.kt`
- `D:\workspace\Saegul\app\src\main\java\org\fcitx\fcitx5\android\ui\main\settings\behavior\PrivacyAiSettingsFragment.kt`
- `D:\workspace\Saegul\app\src\main\java\org\fcitx\fcitx5\android\ui\main\settings\behavior\AdvancedSettingsFragment.kt`
- `D:\workspace\Saegul\app\src\main\java\org\fcitx\fcitx5\android\ui\main\ai\TypingDnaDashboardActivity.kt`
- `D:\workspace\Saegul\app\src\main\java\org\fcitx\fcitx5\android\ui\main\ai\VaultWidgetProvider.kt`
- `D:\workspace\Saegul\app\src\main\java\org\fcitx\fcitx5\android\ui\setup\SetupPage.kt`
- `D:\workspace\Saegul\app\src\main\res\values\saegeul_ui.xml`
- `D:\workspace\Saegul\app\src\main\res\values-night\saegeul_ui.xml`
- `D:\workspace\Saegul\app\src\main\res\values\themes.xml`
- `D:\workspace\Saegul\app\src\main\res\layout\activity_typing_dna_dashboard.xml`
- `D:\workspace\Saegul\app\src\main\AndroidManifest.xml`
- `D:\workspace\Saegul\docs\brand\README.md`
- `D:\workspace\Saegul\docs\wiki\08-Form-Factor-and-Theming.md`
