import XCTest

final class KeyboardSpikeUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testAddKeyboardAndComposeGa() throws {
        addSaegeulKeyboardIfNeeded()

        let app = XCUIApplication()
        app.launch()
        attachScreenshot(named: "01-host-app-launched", app: app)

        let textField = app.textFields["spikeTextField"]
        XCTAssertTrue(textField.waitForExistence(timeout: 10), "spikeTextField가 존재하지 않습니다")
        textField.tap()
        attachScreenshot(named: "02-textfield-tapped", app: app)

        switchToSaegeulKeyboard(in: app)
        attachScreenshot(named: "03-saegeul-keyboard-visible", app: app)

        let keyR = app.descendants(matching: .any)["key.r"]
        let keyK = app.descendants(matching: .any)["key.k"]
        if keyR.waitForExistence(timeout: 5) {
            keyR.tap()
            XCTAssertTrue(keyK.waitForExistence(timeout: 5), "key.k가 존재하지 않습니다")
            keyK.tap()
        } else {
            // SwiftUI 호스팅 키보드는 확장 밖으로 접근성 자식을 노출하지 않는다(실기기 실측 2026-09-14).
            // 실기기 스크린샷(430×932pt)에서 잰 ㄱ(1행 4번째)·ㅏ(2행 8번째) 위치를 앱 창 기준 정규화 좌표로 탭한다.
            attachScreenshot(named: "03b-coordinate-fallback", app: app)
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.350, dy: 0.700)).tap()
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.828, dy: 0.754)).tap()
        }

        waitUntil(timeout: 5) { (textField.value as? String) == "가" }
        XCTAssertEqual(textField.value as? String, "가", "조합 결과가 '가'가 아닙니다: \(String(describing: textField.value))")

        let stepDoneBadge = app.descendants(matching: .any)["onboarding.step.3.done"]
        XCTAssertTrue(stepDoneBadge.waitForExistence(timeout: 5), "onboarding.step.3.done 배지가 나타나지 않았습니다")

        attachScreenshot(named: "04-composed-ga", app: app)

        let keySpace = app.descendants(matching: .any)["key.space"]
        XCTAssertTrue(keySpace.waitForExistence(timeout: 5), "key.space가 존재하지 않습니다")
        keySpace.tap()
        waitUntil(timeout: 5) { (textField.value as? String) == "가 " }
        XCTAssertEqual(textField.value as? String, "가 ", "스페이스 입력 후 값이 '가 '가 아닙니다: \(String(describing: textField.value))")
        attachScreenshot(named: "05-space", app: app)

        let keyBackspace = app.descendants(matching: .any)["key.backspace"]
        XCTAssertTrue(keyBackspace.waitForExistence(timeout: 5), "key.backspace가 존재하지 않습니다")
        keyBackspace.tap()
        keyBackspace.tap()
        waitUntil(timeout: 5) { isEmpty(textField.value as? String) }
        XCTAssertTrue(isEmpty(textField.value as? String), "백스페이스 두 번 후 값이 비어있지 않습니다: \(String(describing: textField.value))")
        attachScreenshot(named: "06-cleared", app: app)

        let keyRAgain = app.descendants(matching: .any)["key.r"]
        XCTAssertTrue(keyRAgain.waitForExistence(timeout: 5), "key.r가 존재하지 않습니다")
        keyRAgain.tap()
        keyBackspace.tap()
        waitUntil(timeout: 5) { isEmpty(textField.value as? String) }
        XCTAssertTrue(isEmpty(textField.value as? String), "초성 하나 지우기 후 값이 비어있지 않습니다: \(String(describing: textField.value))")
        attachScreenshot(named: "07-single-jamo-backspace", app: app)
    }

    func testSecureFieldDoesNotShowSaegeulKeyboard() throws {
        let app = XCUIApplication()
        app.launch()

        let openButton = app.buttons["onboarding.openSecureExample"]
        XCTAssertTrue(openButton.waitForExistence(timeout: 10), "비밀번호 칸 예시 버튼이 존재하지 않습니다")
        if !openButton.isHittable { app.swipeUp() }
        openButton.tap()

        let secureField = app.secureTextFields["spikeSecureField"]
        XCTAssertTrue(secureField.waitForExistence(timeout: 10), "spikeSecureField가 존재하지 않습니다")
        secureField.tap()
        attachScreenshot(named: "e01-securefield-tapped", app: app)

        let keyR = app.descendants(matching: .any)["key.r"]
        let appeared = keyR.waitForExistence(timeout: 2)
        attachScreenshot(named: "e02-after-wait", app: app)
        XCTAssertFalse(appeared, "보안 필드에서도 새글 키보드(key.r)가 나타났습니다")
    }

    func testLatinAndSymbolLayers() throws {
        let app = XCUIApplication()
        app.launch()

        let textField = app.textFields["spikeTextField"]
        XCTAssertTrue(textField.waitForExistence(timeout: 10), "spikeTextField가 존재하지 않습니다")
        textField.tap()
        switchToSaegeulKeyboard(in: app)
        attachScreenshot(named: "layers-00-hangul", app: app)

        XCTAssertTrue(key("key.layer.latin", in: app).waitForExistence(timeout: 5), "key.layer.latin이 존재하지 않습니다")
        key("key.layer.latin", in: app).tap()
        XCTAssertTrue(key("key.latin.a", in: app).waitForExistence(timeout: 5), "key.latin.a가 존재하지 않습니다")
        attachScreenshot(named: "layers-01-latin", app: app)

        key("key.latin.a", in: app).tap()
        key("key.shift", in: app).tap()
        key("key.latin.b", in: app).tap()
        waitUntil(timeout: 5) { (textField.value as? String) == "aB" }
        XCTAssertEqual(textField.value as? String, "aB", "라틴 층 대소문자 입력 결과가 'aB'가 아닙니다: \(String(describing: textField.value))")

        key("key.layer.symbols", in: app).tap()
        XCTAssertTrue(key("key.sym.1", in: app).waitForExistence(timeout: 5), "key.sym.1이 존재하지 않습니다")
        attachScreenshot(named: "layers-02-symbols", app: app)

        XCTAssertTrue(key("key.layer.symbolsAlt", in: app).waitForExistence(timeout: 5), "key.layer.symbolsAlt가 존재하지 않습니다")
        key("key.layer.symbolsAlt", in: app).tap()
        XCTAssertTrue(key("key.sym.[", in: app).waitForExistence(timeout: 5), "key.sym.[가 존재하지 않습니다")
        attachScreenshot(named: "layers-02b-symbolsAlt", app: app)

        XCTAssertTrue(key("key.layer.symbols", in: app).waitForExistence(timeout: 5), "symbolsAlt에서 key.layer.symbols가 존재하지 않습니다")
        key("key.layer.symbols", in: app).tap()
        XCTAssertTrue(key("key.sym.1", in: app).waitForExistence(timeout: 5), "symbols 복귀 후 key.sym.1이 존재하지 않습니다")

        key("key.sym.1", in: app).tap()
        key("key.sym.?", in: app).tap()
        waitUntil(timeout: 5) { (textField.value as? String) == "aB1?" }
        XCTAssertEqual(textField.value as? String, "aB1?", "기호 층 입력 결과가 'aB1?'가 아닙니다: \(String(describing: textField.value))")
        attachScreenshot(named: "layers-03-symbols-typed", app: app)

        XCTAssertTrue(key("key.layer.abc", in: app).waitForExistence(timeout: 5), "key.layer.abc가 존재하지 않습니다")
        key("key.layer.abc", in: app).tap()
        XCTAssertTrue(
            key("key.latin.c", in: app).waitForExistence(timeout: 5),
            "ABC 복귀 후 key.latin.c가 존재하지 않습니다(직전 문자 층이 라틴이어야 합니다)"
        )

        key("key.latin.c", in: app).tap()
        waitUntil(timeout: 5) { (textField.value as? String) == "aB1?c" }
        XCTAssertEqual(textField.value as? String, "aB1?c", "ABC 복귀 후 입력 결과가 'aB1?c'가 아닙니다: \(String(describing: textField.value))")

        XCTAssertTrue(key("key.layer.hangul", in: app).waitForExistence(timeout: 5), "key.layer.hangul이 존재하지 않습니다")
        key("key.layer.hangul", in: app).tap()
        XCTAssertTrue(key("key.r", in: app).waitForExistence(timeout: 5), "한글 층 복귀 후 key.r이 존재하지 않습니다")

        key("key.r", in: app).tap()
        key("key.k", in: app).tap()
        waitUntil(timeout: 5) { (textField.value as? String) == "aB1?c가" }
        XCTAssertEqual(textField.value as? String, "aB1?c가", "한글 조합 결과가 'aB1?c가'가 아닙니다: \(String(describing: textField.value))")
        attachScreenshot(named: "layers-04-back-to-hangul", app: app)

        // 백스페이스 롱프레스: 400ms 지연 후 50ms 간격으로 반복 삭제되는지 확인한다.
        // 완전히 비면 SwiftUI TextField는 placeholder 문자열을 value로 돌려주므로(isEmpty 참고)
        // 길이를 그대로 비교하지 않고 "비었으면 0"으로 취급한다.
        let beforeCount = "aB1?c가".count
        func effectiveLength() -> Int {
            let value = textField.value as? String
            return isEmpty(value) ? 0 : (value?.count ?? 0)
        }

        let backspace = key("key.backspace", in: app)
        XCTAssertTrue(backspace.waitForExistence(timeout: 5), "key.backspace가 존재하지 않습니다")
        backspace.press(forDuration: 1.2)

        waitUntil(timeout: 3) { effectiveLength() <= beforeCount - 2 }
        XCTAssertLessThanOrEqual(
            effectiveLength(), beforeCount - 2,
            "백스페이스 롱프레스 후 길이가 2자 이상 줄지 않았습니다: before=aB1?c가 after=\(String(describing: textField.value))"
        )
        attachScreenshot(named: "layers-05-backspace-repeat", app: app)
    }

    func testLongPressAlternateAndSpaceSwipe() throws {
        let app = XCUIApplication()
        app.launch()

        let textField = app.textFields["spikeTextField"]
        XCTAssertTrue(textField.waitForExistence(timeout: 10), "spikeTextField가 존재하지 않습니다")
        textField.tap()
        switchToSaegeulKeyboard(in: app)
        attachScreenshot(named: "alt-00-hangul", app: app)

        let keyR = key("key.r", in: app)
        XCTAssertTrue(keyR.waitForExistence(timeout: 5), "key.r가 존재하지 않습니다")

        // 새 확정 설계(손을 떼면 그 즉시 확정하고 팝업이 닫힘)에서는 "짧게 눌렀다 떼서 좌표만 잰다"가
        // 성립하지 않는다(떼는 순간 이미 확정·종료돼 버린다). 대신 KeyboardView.enterAlternatesMode의
        // 레이아웃 규칙(카드 폭 = 항목 수×48, 카드 중심 = key.r 중심, fillEqually 2항목)으로
        // key.alt.ㄲ의 화면 좌표를 계산해서 한 번의 연속 제스처(누르고 → 그 좌표로 드래그 → 잠깐 멈춤
        // → 뗌)로 확정까지 마친다.
        let keyboardFrame = key("saegeulKeyboard", in: app).frame
        let keyRFrame = keyR.frame
        // chipCount=2(기본 문자 + 대체 1개), 카드 폭=96, stack 인셋 2·spacing 2 → 항목 폭 45.
        // 두 번째(대체) 항목 중심 = 카드 중심 + 23.5. 카드는 1행이라 위 공간이 없어 topClamp(2pt)로 붙는다.
        let altCenterX = keyRFrame.midX + 23.5
        let altCenterY = keyboardFrame.minY + 2 + 26
        let altTarget = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: altCenterX, dy: altCenterY))

        // 목적지에서 잠깐 멈춰(hold) 하이라이트가 ㄲ으로 옮겨진 순간을 화면 프레임버퍼에서 직접 캡처한다.
        // press(forDuration:thenDragTo:...)는 완전히 끝나야 반환되므로(그 안에서는 스크린샷 호출 불가),
        // 백그라운드 큐에서 hold 도중 XCUIScreen으로 직접 찍는다.
        let highlightBox = ScreenshotBox()
        let captureQueue = DispatchQueue(label: "alt-highlight-capture")
        captureQueue.asyncAfter(deadline: .now() + 1.3) {
            highlightBox.screenshot = XCUIScreen.main.screenshot()
        }
        let keyRCenter = keyR.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        keyRCenter.press(forDuration: 0.6, thenDragTo: altTarget, withVelocity: .slow, thenHoldForDuration: 1.5)
        captureQueue.sync {}
        if let screenshot = highlightBox.screenshot {
            let attachment = XCTAttachment(screenshot: screenshot)
            attachment.name = "alt-01-longpress-popup"
            attachment.lifetime = .keepAlways
            add(attachment)
        } else {
            attachScreenshot(named: "alt-01-longpress-popup", app: app)
        }

        waitUntil(timeout: 5) { (textField.value as? String) == "ㄲ" }
        XCTAssertEqual(
            textField.value as? String, "ㄲ",
            "드래그로 고른 대체 문자가 ㄲ이 아닙니다: \(String(describing: textField.value))"
        )

        key("key.k", in: app).tap()
        waitUntil(timeout: 5) { (textField.value as? String) == "까" }
        XCTAssertEqual(textField.value as? String, "까", "쌍기역 대체 문자 조합 결과가 '까'가 아닙니다: \(String(describing: textField.value))")
        attachScreenshot(named: "alt-02-kka", app: app)

        let spaceKey = key("key.space", in: app)
        XCTAssertTrue(spaceKey.waitForExistence(timeout: 5), "key.space가 존재하지 않습니다")
        spaceKey.tap()
        waitUntil(timeout: 5) { (textField.value as? String) == "까 " }
        XCTAssertEqual(textField.value as? String, "까 ", "스페이스 입력 후 값이 '까 '가 아닙니다: \(String(describing: textField.value))")

        key("key.s", in: app).tap()
        key("key.k", in: app).tap()
        waitUntil(timeout: 5) { (textField.value as? String) == "까 나" }
        XCTAssertEqual(textField.value as? String, "까 나", "값이 '까 나'가 아닙니다: \(String(describing: textField.value))")
        attachScreenshot(named: "alt-03-kka-na", app: app)

        // 스페이스 스와이프: 왼쪽으로 30pt 드래그하면 10pt당 1칸씩, 총 3칸 왼쪽으로 커서가 옮겨져야 한다.
        // "까 나"는 3글자라 3칸 이동하면 커서가 맨 앞(까 앞)으로 간다.
        let beforeSwipe = (textField.value as? String) ?? ""
        let spaceCenter = spaceKey.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        let dragTarget = spaceCenter.withOffset(CGVector(dx: -30, dy: 0))
        spaceCenter.press(forDuration: 0.1, thenDragTo: dragTarget)
        attachScreenshot(named: "alt-04-space-swipe", app: app)

        key("key.layer.latin", in: app).tap()
        XCTAssertTrue(key("key.latin.x", in: app).waitForExistence(timeout: 5), "key.latin.x가 존재하지 않습니다")
        key("key.latin.x", in: app).tap()

        waitUntil(timeout: 5) { (textField.value as? String) != beforeSwipe }
        let afterInsert = (textField.value as? String) ?? ""
        print("SAEGEUL_TEST space-swipe result: before=\(beforeSwipe) after=\(afterInsert) expected(3칸)=x까 나")
        attachScreenshot(named: "alt-05-after-swipe-insert", app: app)

        XCTAssertEqual(afterInsert.count, beforeSwipe.count + 1, "x 한 글자만 삽입돼야 합니다: \(afterInsert)")
        XCTAssertNotEqual(afterInsert, beforeSwipe + "x", "스와이프로 커서가 옮겨졌다면 x가 맨 끝에 붙지 않아야 합니다: \(afterInsert)")
    }

    func testKeyboardHeightStableAcrossReopen() throws {
        let app = XCUIApplication()
        app.launch()

        let textField = app.textFields["spikeTextField"]
        XCTAssertTrue(textField.waitForExistence(timeout: 10), "spikeTextField가 존재하지 않습니다")

        var heights: [CGFloat] = []
        for round in 0..<3 {
            textField.tap()
            switchToSaegeulKeyboard(in: app)
            let saegeulKeyboard = app.descendants(matching: .any)["saegeulKeyboard"]
            XCTAssertTrue(saegeulKeyboard.waitForExistence(timeout: 5), "\(round)회차: saegeulKeyboard가 나타나지 않았습니다")
            heights.append(saegeulKeyboard.frame.height)
            attachScreenshot(named: "height-\(round)", app: app)

            if round < 2 {
                app.swipeDown()
                waitUntil(timeout: 3) { !saegeulKeyboard.exists }
            }
        }

        XCTAssertEqual(heights.count, 3)
        XCTAssertEqual(heights[0], heights[1], accuracy: 0.5, "1·2회차 키보드 높이가 다릅니다: \(heights)")
        XCTAssertEqual(heights[1], heights[2], accuracy: 0.5, "2·3회차 키보드 높이가 다릅니다: \(heights)")
    }

    func testChunjiinLayoutAndOnboardingScrollMask() throws {
        let app = XCUIApplication()
        app.launch()

        // 이 테스트가 중간에 실패해도(assert 실패 시 continueAfterFailure=false로 즉시 중단) 저장된
        // hangulLayout 선호도가 "chunjiin"으로 남아 다른 테스트(두벌식 기본 가정)를 오염시키지 않도록,
        // 무슨 일이 있어도 마지막에 두벌식으로 되돌린다.
        addTeardownBlock {
            let spaceKey = app.descendants(matching: .any)["key.space"]
            guard spaceKey.waitForExistence(timeout: 3) else { return }
            spaceKey.press(forDuration: 0.6)
            let dubeolsikOption = app.descendants(matching: .any)["layout.dubeolsik"]
            if dubeolsikOption.waitForExistence(timeout: 2) {
                dubeolsikOption.tap()
            }
        }

        let textField = app.textFields["spikeTextField"]
        XCTAssertTrue(textField.waitForExistence(timeout: 10), "spikeTextField가 존재하지 않습니다")
        textField.tap()
        switchToSaegeulKeyboard(in: app)

        // F: 키보드가 뜬 상태(콘텐츠가 위로 밀려 화면이 좁아짐)에서 위로 스크롤해, 시계 뒤가
        // 캔버스색 단색으로 덮이는지 스크린샷으로 남긴다.
        app.swipeUp()
        attachScreenshot(named: "onboarding-scrolled", app: app)
        app.swipeDown()

        let spaceKey = key("key.space", in: app)
        XCTAssertTrue(spaceKey.waitForExistence(timeout: 5), "key.space가 존재하지 않습니다")
        spaceKey.press(forDuration: 0.6)
        let chunjiinOption = key("layout.chunjiin", in: app)
        XCTAssertTrue(chunjiinOption.waitForExistence(timeout: 5), "layout.chunjiin이 존재하지 않습니다")
        attachScreenshot(named: "chunjiin-switcher-sheet", app: app)
        chunjiinOption.tap()

        let keyOm = key("key.cj.om", in: app)
        XCTAssertTrue(keyOm.waitForExistence(timeout: 5), "key.cj.om이 존재하지 않습니다(천지인 층 전환 실패)")
        attachScreenshot(named: "chunjiin-layout", app: app)

        keyOm.tap() // ㅇ
        key("key.cj.i", in: app).tap() // ㅣ → 이
        key("key.cj.dot", in: app).tap() // ㆍ → 아
        waitUntil(timeout: 5) { (textField.value as? String) == "아" }
        XCTAssertEqual(textField.value as? String, "아", "쌍기역 없이 ㅇ+ㅣ+ㆍ 결과가 '아'가 아닙니다: \(String(describing: textField.value))")

        key("key.cj.nr", in: app).tap() // ㄴ → 안
        waitUntil(timeout: 5) { (textField.value as? String) == "안" }
        XCTAssertEqual(textField.value as? String, "안")

        Thread.sleep(forTimeInterval: 1.6) // 멀티탭 창(1.5초) 경과: 다음 ㄴ은 새 자모다

        key("key.cj.nr", in: app).tap() // ㄴ(fresh) → "안" 커밋, ㄴ 시작
        waitUntil(timeout: 5) { (textField.value as? String) == "안ㄴ" }
        XCTAssertEqual(textField.value as? String, "안ㄴ")

        key("key.cj.dot", in: app).tap() // ㆍ
        key("key.cj.dot", in: app).tap() // ㆍㆍ
        key("key.cj.i", in: app).tap() // ㅣ → 녀
        key("key.cj.om", in: app).tap() // ㅇ → 녕
        waitUntil(timeout: 5) { (textField.value as? String) == "안녕" }
        XCTAssertEqual(textField.value as? String, "안녕", "천지인으로 입력한 결과가 '안녕'이 아닙니다: \(String(describing: textField.value))")
        attachScreenshot(named: "chunjiin-annyeong", app: app)

        // 자판 전환기로 두벌식으로 복귀한다.
        spaceKey.press(forDuration: 0.6)
        let dubeolsikOption = key("layout.dubeolsik", in: app)
        XCTAssertTrue(dubeolsikOption.waitForExistence(timeout: 5), "layout.dubeolsik이 존재하지 않습니다")
        dubeolsikOption.tap()
        XCTAssertTrue(key("key.r", in: app).waitForExistence(timeout: 5), "두벌식 복귀 후 key.r이 존재하지 않습니다")
    }

    func testNaratgulAndDanmoumLayouts() throws {
        let app = XCUIApplication()
        app.launch()

        // 이 테스트가 중간에 실패해도 hangulLayout 선호도를 무슨 일이 있어도 두벌식으로 되돌린다.
        addTeardownBlock {
            let spaceKey = app.descendants(matching: .any)["key.space"]
            guard spaceKey.waitForExistence(timeout: 3) else { return }
            spaceKey.press(forDuration: 0.6)
            let dubeolsikOption = app.descendants(matching: .any)["layout.dubeolsik"]
            if dubeolsikOption.waitForExistence(timeout: 2) {
                dubeolsikOption.tap()
            }
        }

        let textField = app.textFields["spikeTextField"]
        XCTAssertTrue(textField.waitForExistence(timeout: 10), "spikeTextField가 존재하지 않습니다")
        textField.tap()
        switchToSaegeulKeyboard(in: app)

        let spaceKey = key("key.space", in: app)
        XCTAssertTrue(spaceKey.waitForExistence(timeout: 5), "key.space가 존재하지 않습니다")
        // 정확한 "스페이스 ▾" 표시는 accessibilityLabel이 아니라 화면에 그려진 글자라 스크린샷으로 남긴다.
        attachScreenshot(named: "space-shows-layout-switcher-arrow", app: app)

        spaceKey.press(forDuration: 0.6)
        let switcherSheet = key("layoutSwitcher", in: app)
        XCTAssertTrue(switcherSheet.waitForExistence(timeout: 5), "layoutSwitcher 시트가 존재하지 않습니다")
        let dubeolsikRow = key("layout.dubeolsik", in: app)
        XCTAssertTrue(dubeolsikRow.waitForExistence(timeout: 5), "layout.dubeolsik 행이 존재하지 않습니다")
        // 체크마크 이미지는 버튼(행) 안쪽 서브뷰라 접근성 트리에서 독립적으로 조회되지 않는다(버튼
        // 자신이 accessibility element라 자식이 트리에 별도로 노출되지 않음). 대신 행에 실어 둔
        // accessibilityValue("selected")로 확인하고, 체크마크가 실제로 보이는지는 스크린샷으로 남긴다.
        XCTAssertEqual(dubeolsikRow.value as? String, "selected", "현재 선택된 두벌식 행의 accessibilityValue가 selected여야 합니다")
        attachScreenshot(named: "layout-switcher-sheet", app: app)

        // 나랏글로 전환해 "안녕"을 입력한다.
        let naratgulOption = key("layout.naratgul", in: app)
        XCTAssertTrue(naratgulOption.waitForExistence(timeout: 5), "layout.naratgul이 존재하지 않습니다")
        naratgulOption.tap()

        let keyNgNg = key("key.ng.ng", in: app)
        XCTAssertTrue(keyNgNg.waitForExistence(timeout: 5), "key.ng.ng가 존재하지 않습니다(나랏글 층 전환 실패)")
        attachScreenshot(named: "naratgul-layout", app: app)

        keyNgNg.tap() // ㅇ
        key("key.ng.a", in: app).tap() // ㅏ → 아
        key("key.ng.n", in: app).tap() // ㄴ → 안
        key("key.ng.n", in: app).tap() // ㄴ(직접 키, 순환 아님) → "안" 커밋, ㄴ 시작
        key("key.ng.a", in: app).tap() // ㅏ → 나
        key("key.ng.a", in: app).tap() // 재입력(창 안) → ㅓ로 순환 → 너
        key("key.ng.addstroke", in: app).tap() // ㅓ→ㅕ → 녀
        key("key.ng.ng", in: app).tap() // ㅇ → 녕
        waitUntil(timeout: 5) { (textField.value as? String) == "안녕" }
        XCTAssertEqual(textField.value as? String, "안녕", "나랏글로 입력한 결과가 '안녕'이 아닙니다: \(String(describing: textField.value))")

        // 단모음으로 전환해 "얘기"(ㅐ 연타로 ㅒ)를 입력한다.
        spaceKey.press(forDuration: 0.6)
        let danmoumOption = key("layout.danmoum", in: app)
        XCTAssertTrue(danmoumOption.waitForExistence(timeout: 5), "layout.danmoum이 존재하지 않습니다")
        danmoumOption.tap()

        let keyDmNg = key("key.dm.ng", in: app)
        XCTAssertTrue(keyDmNg.waitForExistence(timeout: 5), "key.dm.ng가 존재하지 않습니다(단모음 층 전환 실패)")
        attachScreenshot(named: "danmoum-layout", app: app)

        keyDmNg.tap() // ㅇ
        let keyDmAe = key("key.dm.ae", in: app)
        // 단모음의 멀티탭 창은 300ms로 짧다. 개별 tap() 두 번은 각자 "앱 idle 대기"를 거쳐
        // 300ms를 넘기기 쉬우므로, 하나의 제스처로 합쳐 보내는 doubleTap()을 쓴다.
        keyDmAe.doubleTap() // ㅐ 연타(창 안) → ㅒ
        key("key.dm.gk", in: app).tap() // ㄱ
        key("key.dm.i", in: app).tap() // ㅣ → 기
        // 텍스트 필드를 나랏글 구간에서 비우지 않았으므로 "안녕" 뒤에 이어 붙는다.
        waitUntil(timeout: 5) { (textField.value as? String) == "안녕얘기" }
        XCTAssertEqual(textField.value as? String, "안녕얘기", "단모음으로 입력한 결과가 '안녕얘기'가 아닙니다: \(String(describing: textField.value))")

        // 자판 전환기로 두벌식으로 복귀한다.
        spaceKey.press(forDuration: 0.6)
        let dubeolsikOption = key("layout.dubeolsik", in: app)
        XCTAssertTrue(dubeolsikOption.waitForExistence(timeout: 5), "layout.dubeolsik이 존재하지 않습니다")
        dubeolsikOption.tap()
        XCTAssertTrue(key("key.r", in: app).waitForExistence(timeout: 5), "두벌식 복귀 후 key.r이 존재하지 않습니다")
    }

    // MARK: - 설정 앱: 키보드 추가

    private func addSaegeulKeyboardIfNeeded() {
        // 시뮬레이터에서는 `simctl spawn ... defaults write`로 키보드를 미리 켜고 설정 앱 자동화를 건너뛴다.
        let env = ProcessInfo.processInfo.environment
        if env["SAEGEUL_SKIP_SETTINGS"] == "1" || env["SIMULATOR_UDID"] != nil { return }
        let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
        settings.launch()
        attachScreenshot(named: "00-settings-launched", app: settings)

        XCTAssertTrue(tapAny(in: settings, labels: ["일반", "General"], timeout: 10), "일반/General 항목을 찾지 못했습니다")
        XCTAssertTrue(tapAny(in: settings, labels: ["키보드", "Keyboard"], timeout: 10), "키보드/Keyboard 항목을 찾지 못했습니다")
        XCTAssertTrue(tapAny(in: settings, labels: ["키보드", "Keyboards"], timeout: 10), "키보드/Keyboards 목록 항목을 찾지 못했습니다")

        if elementExists(in: settings, labels: ["새글"]) {
            settings.terminate()
            return
        }

        XCTAssertTrue(
            tapAny(in: settings, labels: ["AddNewKeyboard", "새로운 키보드 추가", "새 키보드 추가…", "새 키보드 추가...", "Add New Keyboard…", "Add New Keyboard..."], timeout: 10),
            "새 키보드 추가 항목을 찾지 못했습니다"
        )
        XCTAssertTrue(tapAny(in: settings, labels: ["새글"], timeout: 10), "새글 키보드 항목을 찾지 못했습니다")

        settings.terminate()
    }

    private func elementExists(in app: XCUIApplication, labels: [String]) -> Bool {
        findScrollingIfNeeded(in: app, labels: labels, timeout: 5, requireHittable: false) != nil
    }

    @discardableResult
    private func tapAny(in app: XCUIApplication, labels: [String], timeout: TimeInterval) -> Bool {
        guard let element = findScrollingIfNeeded(in: app, labels: labels, timeout: timeout, requireHittable: true) else {
            return false
        }
        element.tap()
        return true
    }

    /// 화면 밖(스크롤 필요)에 있는 셀도 찾도록 필요하면 위로 스와이프하며 재시도한다.
    private func findScrollingIfNeeded(in app: XCUIApplication, labels: [String], timeout: TimeInterval, requireHittable: Bool) -> XCUIElement? {
        let deadline = Date().addingTimeInterval(timeout)
        var scrollAttempts = 0
        repeat {
            for label in labels {
                let candidates: [XCUIElement] = [
                    app.cells.staticTexts[label],
                    app.staticTexts[label],
                    app.buttons[label],
                    app.cells[label],
                ]
                for element in candidates where element.exists {
                    if !requireHittable || element.isHittable {
                        return element
                    }
                }
            }
            if requireHittable && scrollAttempts < 10 {
                app.swipeUp()
                scrollAttempts += 1
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.3))
        } while Date() < deadline
        return nil
    }

    // MARK: - 호스트 앱: 키보드 전환

    private func switchToSaegeulKeyboard(in app: XCUIApplication) {
        let saegeulKeyboard = app.descendants(matching: .any)["saegeulKeyboard"]
        let keyR = app.descendants(matching: .any)["key.r"]
        if saegeulKeyboard.waitForExistence(timeout: 3) || keyR.waitForExistence(timeout: 1) {
            return
        }

        let nextKeyboardLabels = ["Next keyboard", "다음 키보드"]

        // 1차: 지구본을 길게 눌러 여는 전환 팝업에서 "새글" 셀을 직접 탭한다(순환 탭은 실기기에서 새글로 넘어가지 않았다).
        for label in nextKeyboardLabels {
            let globe = app.buttons[label]
            guard globe.exists else { continue }
            globe.press(forDuration: 1.5)
            let saegeulCell = app.cells.matching(NSPredicate(format: "label BEGINSWITH %@", "새글")).firstMatch
            if saegeulCell.waitForExistence(timeout: 3) {
                attachScreenshot(named: "02b-picker-open", app: app)
                saegeulCell.tap()
                if saegeulKeyboard.waitForExistence(timeout: 5) || keyR.waitForExistence(timeout: 3) {
                    return
                }
            } else {
                attachScreenshot(named: "02b-picker-no-saegeul", app: app)
                app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.2)).tap()
            }
            break
        }

        // 2차: 순환 탭.
        for _ in 0..<5 {
            var tapped = false
            for label in nextKeyboardLabels {
                let button = app.buttons[label]
                if button.exists {
                    button.tap()
                    tapped = true
                    break
                }
            }
            if !tapped { break }
            if saegeulKeyboard.waitForExistence(timeout: 3) || keyR.waitForExistence(timeout: 3) {
                return
            }
        }
        XCTAssertTrue(
            saegeulKeyboard.waitForExistence(timeout: 3) || keyR.waitForExistence(timeout: 3),
            "새글 키보드로 전환하지 못했습니다"
        )
    }

    // MARK: - 공통 유틸

    private func key(_ identifier: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any)[identifier]
    }

    private func waitUntil(timeout: TimeInterval, condition: () -> Bool) {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() && Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.1))
        }
    }

    private func attachScreenshot(named name: String, app: XCUIApplication) {
        let screenshot = app.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}

/// 롱프레스 드래그 도중(제스처 호출이 아직 안 끝난 상태) 백그라운드 큐에서 찍은 화면 스크린샷을
/// 메인 테스트 코드로 넘기기 위한 상자. `XCUIScreen.main.screenshot()`는 화면 프레임버퍼를 직접
/// 찍으므로 `press(forDuration:thenDragTo:...)` 호출이 메인 스레드를 막고 있어도 병행 호출할 수 있다.
private final class ScreenshotBox: @unchecked Sendable {
    var screenshot: XCUIScreenshot?
}

private func isEmpty(_ value: String?) -> Bool {
    guard let value else { return true }
    return value.isEmpty || value == "여기에 가를 입력해 보세요"
}
