# Android 자판·입력 엔진 인벤토리 (2026-09-14)

읽기 전용 탐색으로 수집했다. 추측 없이 파일에서 확인한 것만 적었다. 근거의 상대 경로는 별도 표기가 없으면 `app/src/main/java/org/fcitx/fcitx5/android/input/` 아래이고, 저장소 루트는 `D:\workspace\Saegul\`이다. iOS 1:1 이식 격차표의 기준 문서로 쓴다.

---

## A. 한글 조합 엔진 자판 (fcitx5-hangul `Keyboard` 옵션, 9종)

엔진 쪽 자판은 `plugin/hangul/src/main/cpp/fcitx5-hangul/src/engine.h:35-45`의 `HangulKeyboard` enum이 정본이고, 앱은 같은 표를 `keyboard/HangulKeyboardTables.generated.kt`(libhangul 리비전 `a34aef73378c0992316861bbf13fc914ee7577d9`)로 복제해 키 라벨을 그린다.

| # | 자판 | 엔진 근거 | 앱 라벨 테이블 | 앱 표면 | 비고 |
|---|---|---|---|---|---|
| 1 | Dubeolsik | `engine.h:36` | `HangulKeyboardTables.generated.kt:11` | TextKeyboard 또는 모바일 표면 | 기본값. 모바일 표면 전환의 유일한 진입 조건 |
| 2 | Dubeolsik Yetgeul | `engine.h:37` | `:29` | TextKeyboard | `fullSurfaceLayouts`에 없음 |
| 3 | Sebeolsik 390 | `engine.h:38` | `:47` | HangulKeyboard(전체 물리 표면) | `keyboard/HangulKeyLegends.kt:169-176` |
| 4 | Sebeolsik Final | `engine.h:39` | `:65` | HangulKeyboard | 동일 |
| 5 | Sebeolsik Noshift | `engine.h:40` | `:83` | HangulKeyboard | 동일 |
| 6 | Sebeolsik Yetgeul | `engine.h:41` | `:101` | HangulKeyboard | 동일 |
| 7 | Sebeolsik Dubeol Layout | `engine.h:42` | `:119` | HangulKeyboard | 동일 |
| 8 | Romaja | `engine.h:43` | `:137` | TextKeyboard | `HangulKeyLegends.kt:167`에서 라벨 대상에서 제외 |
| 9 | Ahnmatae | `engine.h:44` | `:155` | HangulKeyboard | 동일 |

엔진 자판 이름의 한국어 표기는 앱 `strings.xml`이 아니라 `plugin/hangul/src/main/assets/usr/share/locale/ko/LC_MESSAGES/fcitx5-hangul.mo`가 제공한다. 이식 시 별도 번역 소스가 필요하다.

부속 자산: 한자 표 `plugin/hangul/src/main/assets/usr/share/libhangul/hanja/hanja.txt`, MS 기호 표 `mssymbol.txt`, 단어 완성 사전 `usr/share/fcitx5/hangul/completion.txt`, 다음 단어 `nextword.txt`, 기호 `symbol.txt`.

---

## B. 앱 키보드 표면 (BaseKeyboard 구현체 4종 + 툴바 1종)

| # | 표면 | 근거 | 설명 |
|---|---|---|---|
| 10 | `TextKeyboard` | `keyboard/TextKeyboard.kt:23-86` | QWERTY 10/9/9 + 하단행. 한글 IME일 때 자모 라벨로 치환 |
| 11 | `HangulKeyboard` | `keyboard/HangulKeyboard.kt:31-57` | 물리 자판 전체 표면(13 / 12 / 12+백스페이스 / Caps+10 / 하단행). 세벌식·안마태 전용 |
| 12 | `MobileHangulKeyboard` | `keyboard/MobileHangulKeyboard.kt:49-53` | 모바일 한글 표면 9종의 공통 뷰 |
| 13 | `NumberKeyboard` | `keyboard/NumberKeyboard.kt:346-385` | 숫자 키패드. 팝업 전면 비활성(`:396-399`) |
| 14 | 툴바 `NumberRow` | `bar/ui/idle/NumberRow.kt:21` | 비밀번호 입력란용 임시 숫자행. 오른쪽 스와이프로 접힘(`:28-44`) |

표면 선택 로직은 `keyboard/KeyboardWindow.kt:194-207`. 숫자·전화 입력란이면 `NumberKeyboard`로 자동 전환된다(`:269-274`). 표면 전환 기억은 `keyboard/KeyboardLayoutMemory.kt:292-300`이 담당하고, `?123` 키는 항상 기호 표면만 가리킨다.

---

## C. 모바일 한글 표면 (`MobileHangulLayout`, 10개 항목)

정의는 `keyboard/MobileHangulLayout.kt:10-21`, 레이아웃 빌더는 `keyboard/MobileHangulKeyboard.kt:379-390`, 사용자에게 보이는 이름은 `app/src/main/res/values-ko/strings.xml:126-135`.

| # | enum | 한국어 라벨 | 레이아웃 근거 | 조합 방식 |
|---|---|---|---|---|
| 15 | `Physical` | 물리 자판 배열 | `keyboard/MobileHangulSurfaceSwitcher.kt:235-236` | 표면 전환 안 함(TextKeyboard 유지) |
| 16 | `Chunjiin` | 천지인 | `MobileHangulKeyboard.kt:140-167` | ㅣ/ㆍ/ㅡ 3키 + 자음 멀티탭 4행 |
| 17 | `ChunjiinPlus` | 천지인 플러스 | `:169-203` | 자음 분리 배치, `cycleAlt`로 보조 라벨 표시 |
| 18 | `Danmoum` | 단모음 | `:205-232` | 8열 3행 + QWERTY형 하단행, 멀티탭 300ms |
| 19 | `MoakeyOneHand` | 한손 모아키 | `:254-289` (`oneHand=true`) | 자음 키 제스처 + 하단 `ㆍ ㅣ ㅡ` 단독 모음 키 |
| 20 | `MoakeyTwoHand` | 양손 모아키 | `:254-289` (`oneHand=false`) | 자음 키 제스처 + 우측 `ㅣ`/`ㅡ`/`ㆍ` 키 |
| 21 | `Vega` | 베가 | `:314-333` (`centered=false`) | 3열 코어 + 우측 기능열 |
| 22 | `VegaCenter` | 베가(중앙) | `:314-333` (`centered=true`) | 좌우 양쪽에 기능열, 코어를 가운데로 |
| 23 | `Naratgul` | 나랏글 | `:358-377` (`centered=false`) | 획추가·쌍자음 키 |
| 24 | `NaratgulCenter` | 나랏글(중앙) | `:358-377` (`centered=true`) | 좌우 기능열 배치 |

설정 이름은 "한글 키보드 표면"(`mobile_hangul_layout`, `data/prefs/AppPrefs.kt:90-94`). 스페이스바 라벨에 `▾`가 붙고 길게 누르면 선택 대화상자가 뜬다(`KeyboardWindow.kt:105-123`, `strings.xml:136-137`).

**중요한 제약**: 모바일 표면은 엔진 자판이 `Dubeolsik`이거나 `"0"`일 때만 활성화된다(`MobileHangulSurfaceSwitcher.kt:232`). 세벌식·안마태에서는 선택할 수 없다.

모든 모바일 표면에는 `PinnedNumberRow.prependTo`가 적용되어 숫자 행 설정을 따른다(`MobileHangulKeyboard.kt:53`).

---

## D. 모바일 한글 조합기 (`MobileHangulComposer`)

모든 모바일 표면은 두벌식 키 문자열로 변환해 엔진에 보낸다(`keyboard/MobileHangulComposer.kt:387`, 매핑표 `:447-457`). 즉 엔진은 항상 두벌식 하나만 쓴다.

| # | 기능 | 근거 | 비고 |
|---|---|---|---|
| 25 | 멀티탭 순환(`Cycle`) | `MobileHangulComposer.kt:287-323` | 기본 1500ms, 단모음 자판은 300ms (`:417-418`) |
| 26 | 천지인 `ㆍ` 점 누적 | `:333-354` | 점 2개까지 누적 후 ㅣ/ㅡ와 결합 |
| 27 | 천지인 모음 결합표 | `:420-424` | ㅏ+ㅣ→ㅐ 등 10쌍 |
| 28 | 일반 모음 결합표 | `:426-432` | ㅗ+ㅏ→ㅘ 등 13쌍 |
| 29 | 나랏글 획추가 | `:434-439` | ㄱ→ㅋ, ㅏ→ㅑ 등 12쌍 |
| 30 | 나랏글 쌍자음 토글 | `:441-445` | ㄱ↔ㄲ 등 왕복 5쌍 |
| 31 | 나랏글 ㅜ+ㅏ→ㅝ 예외 | `:404-412` | `naratgulVowelPair` 플래그 |
| 32 | 멀티탭 중 스페이스 = 조합 종료 | `:379-383` | 타임아웃 내 스페이스는 공백을 넣지 않고 멀티탭만 닫음 |
| 33 | 모아키 8방향 제스처 | `keyboard/MoakeyGestureRecognizer.kt:218-261` | 임계값 28f, Zone 7종. 되돌림(return)·회전(turn) 인식으로 ㅑ/ㅐ/ㅘ/ㅙ/ㅚ/ㅝ/ㅞ/ㅟ/ㅢ 구분 |

조합기 출력은 `Output.Backspace` / `Output.Space` / `Output.Keys` 3종이고(`:233-237`), `MobileHangulKeyboard.dispatch`(`:412-426`)가 각각 BackSpace 심볼, space 심볼, `FcitxKeyAction`으로 변환한다.

---

## E. 키 동작 계약

키는 `KeyDef.Behavior` 6종으로 정의되고(`keyboard/KeyDef.kt:93-117`), `BaseKeyboard.createKeyView`(`keyboard/BaseKeyboard.kt:184-399`)가 실제 리스너로 바인딩한다.

| # | 동작 | 근거 | 사용자에게 보이는 설정 이름 | 비고 |
|---|---|---|---|---|
| 34 | Press | `BaseKeyboard.kt:247-251` | — | |
| 35 | LongPress | `:252-257` | 키 길게 누름 지연 (`keyboard_long_press_delay`, 기본 300ms, 100~700) | `AppPrefs.kt:119-127`, 대기는 `keyboard/CustomGestureView.kt:152-161` |
| 36 | Repeat | `:258-264` | 키 반복 시 햅틱 피드백 (`haptic_on_repeat`) | 반복 간격 50ms 고정(`CustomGestureView.kt:310`). 실제 사용처는 백스페이스뿐(`keyboard/KeyDefPreset.kt:141`) |
| 37 | Swipe(문장부호/숫자) | `:265-283` | 밀어서 문장 부호 및 숫자 입력 (`swipe_symbol_behavior`: 위로 밀기 / 아래로 밀기 / 사용 안 함, 기본 아래로) | `keyboard/SwipeSymbolDirection.kt`, 임계값 dp(36) |
| 38 | Gesture | `:284-296` | — | 모아키 전용, 임계값 dp(10) |
| 39 | DoubleTap | `:297-302` | — | Caps 키만 사용(`KeyDefPreset.kt:106`) |
| 40 | 스페이스 스와이프 커서 이동 | `:198-221` | 스페이스 키를 밀어 커서 이동 (`space_swipe_move_cursor`, 기본 켜짐) | dp(10)마다 좌/우 방향키 1회 |
| 41 | 백스페이스 스와이프 선택 삭제 | `:222-244` | 설정 없음(항상 켜짐) | Move로 선택 확장, Up에서 `DeleteSelectionAction`. 상태 머신은 `keyboard/CommonKeyActionListener.kt:161-195` |
| 42 | 스페이스 롱프레스 | `KeyDefPreset.kt:222`, `CommonKeyActionListener.kt:205-216` | 스페이스 키 길게 누르기 동작 (없음 / 입력기 순환 / 입력기 활성 상태 전환 / 입력기 선택 대화상자 표시) | 두벌식일 때는 한글 표면 선택기가 우선(`KeyboardWindow.kt:98-100`) |
| 43 | 언어 전환 키 | `KeyDefPreset.kt:195-206` | 언어 전환 키 표시 (`show_lang_switch_key`) / 언어 전환 키 동작 (입력기 순환 · 입력기 활성 상태 전환 · 다음 입력기 앱으로 전환) | 길게 누르면 시스템 입력기 선택 대화상자. `keyboard/LangSwitchBehavior.kt:263-267` |
| 44 | Shift 3단계 | `TextKeyboard.kt:28`, `HangulKeyboard.kt:33` | — | None / Once / Lock. 롱프레스 또는 더블탭으로 Lock. 아이콘 `ic_capslock_none/once/lock` |
| 45 | 대문자 라벨 고정 | `TextKeyboard.kt:103` | 키보드 글자를 대문자로 유지 (`keep_keyboard_letters_uppercase`) | 한글 IME일 때는 무시되고 자모 라벨이 우선 |
| 46 | 키 팝업 미리보기 | `BaseKeyboard.kt:377-395` | 키를 누를 때 팝업 표시 (`popup_on_key_press`, 기본 켜짐) | |
| 47 | 스와이프 방향 미리보기(AltPreview) | `:352-376` | 동일 | 스와이프 중 대체 문자로 라벨 교체 |
| 48 | 롱프레스 팝업 키보드 | `:330-351`, `popup/PopupKeyboardUi.kt:45-` | — | 프리셋 114개 키(`popup/PopupPreset.kt`) |
| 49 | 롱프레스 팝업 메뉴 | `:308-329` | — | 쉼표 키 → 이모지·빠른 문구·유니코드(`KeyDefPreset.kt:171-192`), 엔터 키 → 이모지(`:238-246`) |
| 50 | 멀티터치 라우팅 | `BaseKeyboard.kt:476-545` | — | 포인터별 타깃 추적(`touchTarget`) |
| 51 | 키 입력 영역 확장 | `BaseKeyboard.kt:61` | 키 입력 영역을 가장자리까지 확장 (`expand_keypress_area`) | |
| 52 | vivo OriginOS 키 입력 우회 | `BaseKeyboard.kt:72` | (고급) vivo 키 입력 우회 | Android 14 미만 + OriginOS일 때 기본 켜짐 |

`KeyAction` 전체 목록은 `keyboard/KeyAction.kt:147-182`: FcitxKey / Sym / Commit / MobileHangul / MobileHangulSequence / Caps / QuickPhrase / Unicode / LangSwitch / ShowInputMethodPicker / LayoutSwitch / MoveSelection / DeleteSelection / PickerSwitch / SpaceLongPress.

---

## F. 한글 조합·프리에딧

| # | 기능 | 근거 | 사용자에게 보이는 설정 이름 | 비고 |
|---|---|---|---|---|
| 53 | 표준 composing 스팬 | `FcitxInputMethodService.kt:5248-5303` | — | `setComposingText` + 하이라이트 스팬 |
| 54 | 조합 중 커서 이동 → 엔진 반영 | `:5216-5229` | — | 시스템 커서 위치를 코드포인트로 변환해 `moveCursor` |
| 55 | 조합 범위 밖 커서 이동 처리 | `:5230-5240` | — | `finishComposingText` 후 focus out/in |
| 56 | 시스템 커서 무시 | `:5217` | 시스템 커서 무시 (`ignore_system_cursor`) | |
| 57 | 한글 버퍼 호환 모드 | `BufferedHangulMode.kt:17-27` | 한글 버퍼 호환 모드 (`buffered_hangul_input`) | 켜면 `CapabilityFlag.Preedit`를 꺼서 앱에 프리에딧을 보내지 않음 |
| 58 | 버퍼 전달 방식 3종 | `BufferedInputTransport.kt:10-14` | 버퍼 입력 전달 방식 (시스템 붙여넣기 / Ctrl+V / 직접 확정) | `strings.xml:138-141` |
| 59 | 버퍼 세션 상태 머신 | `BufferedInputController.kt:33-51` | — | Idle / Composing / Submitted / PreservedForRetry / Discarded |
| 60 | 버퍼 모드 전용 창 | `BufferedHangulWindow.kt:42-` | 툴바 버튼으로 진입 | 끄기 + 전달 방식 3종, 총 4개 모드 카드 |
| 61 | 버퍼 모드 비밀번호·민감 입력란 클립보드 회피 | `BufferedHangulMode.kt:29-31` | — | |
| 62 | 호환 대상 앱 자동 판별 | `BufferedHangulMode.kt:33-50` | — | Microsoft / .NET / Unity / Valve / Termux / RealVNC / TeamViewer / AnyDesk / Parsec / Moonlight 10개 prefix |
| 63 | 엔진 옵션: Auto Reorder | `engine.h:89` | fcitx 입력기 설정 화면 | 기본 켜짐 |
| 64 | 엔진 옵션: Word Commit | `engine.h:98` | 동일 | 기본 꺼짐 |
| 65 | 엔진 옵션: 한자 모드·토글 키 | `engine.h:58-63`, `:99` | 동일 | 토글 키 기본 `Hangul_Hanja`, `F9` |
| 66 | 엔진 옵션: 한국어 단어 완성 | `engine.h:100-101` | 동일 | 기본 켜짐 |
| 67 | 후보 정책(완성 vs 한자) | `plugin/hangul/src/main/cpp/fcitx5-hangul/src/candidatepolicy.h:14-22` | — | 단어 완성이 켜지면 한자는 1회성 액션으로 강등 |

엔진 옵션 중 페이지·후보 이동 키(`PrevPage` `Up`, `NextPage` `Down`, `PrevCandidate` `Shift+Tab`, `NextCandidate` `Tab`)는 `engine.h:64-87`에 있다. libhangul 0.2 빌드에서만 `CombiOnDoubleStroke`(기본 꺼짐), `NonChoseongCombi`(기본 켜짐)가 노출된다(`engine.h:91-97`).

백스페이스는 별도 자모 삭제 로직 없이 `FcitxKey_BackSpace` 심볼을 엔진에 보낸다(`KeyDefPreset.kt:140-141`). 자모 단위 삭제는 libhangul이 담당한다. 모바일 표면에서만 조합기가 자체 `Output.Backspace`를 끼워 넣어 멀티탭 교체를 구현한다(`MobileHangulKeyboard.kt:412-426`).

---

## G. 키보드 크기·폼팩터

| # | 기능 | 근거 | 사용자에게 보이는 설정 이름 | 범위·기본값 |
|---|---|---|---|---|
| 68 | 키보드 높이(세로/가로) | `AppPrefs.kt:143-161`, 적용 `InputView.kt:184-192` | 키보드 높이 | 10~90%, 세로 30 / 가로 49 |
| 69 | 좌우 여백(세로/가로) | `AppPrefs.kt:163-181`, `InputView.kt:194-202` | 키보드 좌우 여백 | 0~300dp, 기본 0 |
| 70 | 아래쪽 여백(세로/가로) | `AppPrefs.kt:183-201`, `InputView.kt:203-210` | 키보드 아래쪽 여백 | 0~100dp, 기본 0 |
| 71 | 숫자 행 항상 표시 | `keyboard/PinnedNumberRow.kt:299-318` | 숫자 행 항상 표시 (`show_number_row`) | 기본 꺼짐. 켜면 높이를 5/4배로 자동 보정하고 90%에서 자름(`:324-329`) |
| 72 | 숫자 행 pin 시 상단행 스와이프 기호 교체 | `TextKeyboard.kt:33-47` | — | 숫자 대신 `%^&_[]{}<>` |
| 73 | 분할 키보드(휴대폰·커버) | `AppPrefs.kt:203-208`, `keyboard/FoldKeyboardProfile.kt:45-88` | 휴대폰·커버 화면 분할 키보드 | 기본 꺼짐 |
| 74 | 분할 키보드(펼친 화면·태블릿) | `AppPrefs.kt:209-214` | 펼친 화면·태블릿 분할 키보드 | 가로·세로 모두 600dp 이상일 때만(`FoldKeyboardProfile.kt:46`) |
| 75 | 분할 중앙 간격 4종 | `AppPrefs.kt:216-255` | 휴대폰·커버 중앙 간격 / 펼친 화면·태블릿 중앙 간격 | 24~180dp(기본 48·72), 32~240dp(기본 96·128) |
| 76 | QWERTY 손 경계 | `FoldKeyboardProfile.kt:163-177` | — | 행별 경계 `[5,5,5,3]`, 숫자행은 5 |
| 77 | 분할 행 기하 계산 | `FoldKeyboardProfile.kt:132-160` | — | 간격은 너비의 1/3까지, 키 축소는 55%까지 제한 |
| 78 | 숫자 키패드는 분할 제외 | `KeyboardWindow.kt:254` | — | |
| 79 | 가로/세로별 키 여백 | `data/theme/ThemePrefs.kt:46-84` | 키 좌우 여백 / 키 상하 여백 | 0~24dp. 좌우 3·3, 상하 7·4 |
| 80 | 키 모서리 반경 | `ThemePrefs.kt:86` | 키 모서리 반경 | 0~48dp, 기본 4 |
| 81 | 키 테두리·테두리 선·물결 효과 | `ThemePrefs.kt:37-44` | 키 테두리 / 키 테두리 선 / 키 물결 효과 | 모두 기본 꺼짐 |

한손 모드와 플로팅(떠 있는) 키보드는 **없다**. "한손" 문자열은 `mobile_hangul_moakey_one_hand`(한손 모아키) 하나뿐이고(`strings.xml:130`), `AppPrefs`에 one-hand / floating 키보드 설정이 없다. `FloatingCandidates*`는 후보 창 전용이다(`AppPrefs.kt:17-18`).

키 글꼴 크기 설정도 없다. 키 텍스트 크기는 `KeyDef`에 하드코딩되고(알파벳 23f, 한글 물리 20f, 모바일 한글 19f, 숫자패드 30f) `AutoScaleTextView`가 축소만 담당한다. 조절 가능한 글꼴 크기는 후보 단어용(`candidates_window_font_size`, 4~64sp)뿐이다.

---

## H. 부가 입력 창·툴바

| # | 기능 | 근거 | 사용자에게 보이는 설정 이름 |
|---|---|---|---|
| 82 | 기호 피커 | `picker/PickerWindowPreset.kt:13-18`, 데이터 `picker/PickerData.kt:15` | — |
| 83 | 이모지 피커 | `PickerWindowPreset.kt:20-28`, `PickerData.kt:95` | 지원되지 않는 이모지 숨기기 |
| 84 | 이모티콘 피커 | `PickerWindowPreset.kt:30-37`, `PickerData.kt:262` | — |
| 85 | 최근 사용 카테고리 | `PickerData.kt:13`, `picker/PickerWindow.kt:77-79` | — |
| 86 | 이모지 피부색 변경자 | `AppPrefs.kt` Symbols 그룹, `popup/EmojiModifier.kt` | 기본 이모지 피부색 변경자 |
| 87 | 커서 이동 패드(텍스트 편집 창) | `editing/TextEditingUi.kt:49-90` | 툴바 버튼 "텍스트 편집" |
| 88 | 편집 패드 버튼 12종 | `editing/TextEditingUi.kt:49-90` | 상 / 하 / 좌 / 우 / 처음 / 끝 / 선택 / 전체 선택 / 잘라내기 / 복사 / 붙여넣기 / 백스페이스 |
| 89 | 클립보드 창 | `clipboard/ClipboardWindow.kt:56-186` | 클립보드 수신 대기 · 클립보드 기록 제한 · 클립보드 추천 표시 · 클립보드 추천 표시 시간 · 붙여넣은 후 돌아가기 · 민감한 데이터 가리기 |
| 90 | 음성 입력 | `voice/VoiceTranscriptionWindow.kt:33-` | 음성 입력 버튼 표시 · 선호하는 음성 입력 |
| 91 | 음성 제공자 3종 | `VoiceTranscriptionWindow.kt:115-127` | 기기 받아쓰기 / OpenAI Realtime / OpenAI API |
| 92 | 툴바 버튼 13개 | `bar/ui/idle/ButtonsBarUi.kt:148-198` | 실행 취소 · 다시 실행 · 커서 이동 · 클립보드 · 한글 버퍼 · 빠른 문구 · 한국어 검색 · 오타 복구 · AI · 정밀 받아쓰기 · OCR · GIF · 더보기 |
| 93 | 툴바 1줄/2줄 전환 | `ButtonsBarUi.kt:45-142` | 도구 모음을 기본으로 펼치기 |
| 94 | 인라인 자동 완성 제안 | `AppPrefs.kt` Keyboard 그룹 | 인라인 자동 완성 제안 표시 |
| 95 | 앱별 키보드 프로필 | `profile/AppKeyboardProfile.kt:34-41` | 앱마다 모바일 한글 표면 · 테마 · 툴바 · 버퍼 전달 방식 · 네트워크 · AI 정책을 재정의 |

---

## I. 피드백·시각 효과

| # | 기능 | 근거 | 사용자에게 보이는 설정 이름 |
|---|---|---|---|
| 96 | 키 누름 햅틱 | `data/InputFeedbacks.kt:44`, `CustomGestureView.kt:149` | 키를 누를 때 햅틱 피드백 (시스템 따름 / 켜짐 / 꺼짐) |
| 97 | 키 뗌 햅틱 | `InputFeedbacks.kt:45`, `CustomGestureView.kt:183` | 키에서 손을 뗄 때 햅틱 피드백 |
| 98 | 반복 시 햅틱 | `BaseKeyboard.kt:74` | 키 반복 시 햅틱 피드백 |
| 99 | 진동 시간(누름/길게) | `AppPrefs.kt:113-130` | 키 누름 진동 시간, 0~100ms, 기본 시스템 기본값 |
| 100 | 진동 세기(누름/길게) | `AppPrefs.kt:132-155` | 키 누름 진동 세기, 0~255. 진폭 제어 지원 기기에서만 노출 |
| 101 | 키 소리 | `InputFeedbacks.kt:107-125` | 키 입력 소리 · 키 입력 소리 크기(0~100%) |
| 102 | 소리 종류 4가지 | `KeyDef.kt:22`, `BaseKeyboard.kt:191-197` | Standard / SpaceBar / Delete / Return |
| 103 | RGB 크로마 백라이트 | `BaseKeyboard.kt:90`, `keyboard/effects/RgbChromaEffectView.kt` | 테마 정의(`data/theme/Theme.kt:60`)로 켜짐. 별도 설정 아님 |
| 104 | 터치 파티클 오버레이 | `BaseKeyboard.kt:91`, `keyboard/effects/ParticleTouchOverlayView.kt` | 테마 정의(`Theme.kt:61`) |
| 105 | 문장 부호 위치 | `ThemePrefs.kt:94-104` | 문장 부호 위치 |
| 106 | 포커스 변경 시 키보드 복귀 | `AppPrefs.kt` Keyboard 그룹 | 포커스가 변경되면 키보드로 돌아가기 |

오토 캡(자동 대문자)은 **없다**. `autoCap` · `AutoCap` · `auto_cap` · `capitalize` · `Capitalization` 전체 검색 결과가 0건이다. `keepLettersUppercase`는 라벨 표시 전용이고 입력 자체를 대문자화하지 않는다.

---

## 기능 총수

**106개**. 구성은 엔진 자판 9, 앱 표면 5, 모바일 한글 표면 10, 모바일 조합기 9, 키 동작 19, 한글 조합·프리에딧 15, 크기·폼팩터 14, 부가 창·툴바 14, 피드백·효과 11이다.

---

## iOS 이식 시 특히 주의할 격차 3가지

1. **모바일 표면은 엔진에 자모를 보내지 않는다.** 두벌식 ASCII 키 문자열로 변환해 보낸다(`MobileHangulComposer.kt:387`, `:447-457`). iOS에서 libhangul을 쓰든 자체 조합기를 쓰든 이 계약을 그대로 지켜야 천지인·나랏글·모아키가 같은 결과를 낸다.
2. **모바일 표면 활성 조건이 엔진 자판에 묶여 있다.** `Dubeolsik` 또는 `"0"`일 때만 켜진다(`MobileHangulSurfaceSwitcher.kt:232`). 세벌식 6종은 물리 전체 표면(13열)을 요구하므로 아이폰 화면 폭에 그대로 이식하면 키가 너무 좁아진다.
3. **버퍼 호환 모드는 Android `InputConnection` 고유의 우회책이다.** 시스템 붙여넣기와 Ctrl+V 에뮬레이션은 iOS `UITextDocumentProxy`에 대응물이 없다. 직접 확정만 이식 가능하고, 나머지 둘은 격차표에서 "플랫폼 비해당"으로 분류해야 한다.

---

## 문서 불일치

`docs/wiki/02-Keyboard-Layouts-and-Engine.md:3`은 "17가지 자판"이라 하고 `:25-28`에서 세벌식 순이 · 세벌식 3-91 · 세벌식 3-93 · 세벌식 단모음, `:20`에서 단모음 두벌식을 열거하지만 코드에는 그런 엔진 자판이 없다. 실제는 엔진 자판 9종(`engine.h:35-45`) + 앱 모바일 표면 9종(`MobileHangulLayout.kt:10-21`, `Physical` 제외)이다. 위키의 "단모음"은 엔진 자판이 아니라 모바일 표면 `Danmoum`이고, "세벌식 순이"는 엔진 이름 `Sebeolsik Noshift`의 별칭으로 보이나 코드에 그 표기는 없다.

같은 문서 `:31`의 "안마태(Ahnmatae)"는 엔진 자판 `Ahnmatae`(`engine.h:44`)로 실재한다.

`docs/wiki/08-Form-Factor-and-Theming.md:11`의 "기기 힌지가 펼쳐지면 분할 키보드로 자동 전환"은 코드와 다르다. `FoldKeyboardProfile.kt:40-43` 주석이 명시하듯 힌지 자세는 IME에 노출되지 않을 수 있어 창·설정 크기만 보고 판단하며, 분할은 사용자가 설정을 켠 경우에만 적용된다(`:54-58`). 같은 문서 `:14-17`의 "각 화면 상태별로 크기·높이·키 배열 설정을 독립적으로 기억"도 코드상으로는 세로/가로 2쌍 설정과 compact/expanded 2쌍 간격 설정이 전부이고, 화면 상태별 독립 높이 프로필은 없다.

`docs/wiki/08-Form-Factor-and-Theming.md:35`의 "터미널·개발 앱에 Ctrl/Alt 보조열 표시"는 코드에 없다. `AppKeyboardProfile`(`profile/AppKeyboardProfile.kt:34-41`)이 재정의하는 항목은 모바일 한글 표면 · 테마 이름 · 툴바 표시 · 버퍼 전달 방식 · 네트워크 정책 · AI 정책 6가지뿐이고, 보조 키열 항목은 없다.
