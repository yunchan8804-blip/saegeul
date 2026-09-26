import XCTest
@testable import HangulCore

private final class NaratgulTestClock {
    var now = 0
    func advance(by ms: Int) { now += ms }
}

final class NaratgulGoldenTests: XCTestCase {
    private func makeInput(_ clock: NaratgulTestClock, windowMs: Int = 1500) -> NaratgulInput {
        NaratgulInput(multiTapWindowMs: windowMs, now: { clock.now })
    }

    private func press(
        _ key: NaratgulKey, input: NaratgulInput, composer: HangulComposer, accumulated: inout String
    ) {
        let event = input.press(key)
        for _ in 0..<event.backspaces {
            _ = composer.backspace()
        }
        for jamo in event.jamo {
            accumulated += composer.process(jamo: jamo).commit
        }
    }

    private func visible(_ accumulated: String, _ composer: HangulComposer) -> String {
        accumulated + composer.preedit
    }

    // MARK: - 단어

    func testAnnyeong() {
        let clock = NaratgulTestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.ng, input: input, composer: composer, accumulated: &acc) // ㅇ
        press(.a, input: input, composer: composer, accumulated: &acc) // ㅏ → 아
        press(.n, input: input, composer: composer, accumulated: &acc) // ㄴ → 안
        press(.n, input: input, composer: composer, accumulated: &acc) // ㄴ(직접 키라 창 무관) → "안" 커밋, ㄴ 시작
        press(.a, input: input, composer: composer, accumulated: &acc) // ㅏ → 나
        press(.a, input: input, composer: composer, accumulated: &acc) // ㅏ 재입력(창 안) → ㅓ로 순환 → 너
        press(.addStroke, input: input, composer: composer, accumulated: &acc) // ㅓ→ㅕ → 녀
        press(.ng, input: input, composer: composer, accumulated: &acc) // ㅇ → 녕
        XCTAssertEqual(visible(acc, composer), "안녕")
    }

    /// ㄱ+쌍자음(ㄲ)+ㅗ(순환 첫 후보)+ㅅ(받침)+획추가(ㅅ→ㅈ)+획추가(ㅈ→ㅊ) → 꽃
    func testGgot() {
        let clock = NaratgulTestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.g, input: input, composer: composer, accumulated: &acc)
        press(.doubleConsonant, input: input, composer: composer, accumulated: &acc)
        press(.o, input: input, composer: composer, accumulated: &acc)
        press(.s, input: input, composer: composer, accumulated: &acc)
        press(.addStroke, input: input, composer: composer, accumulated: &acc)
        press(.addStroke, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "꽃")
    }

    /// ㅁ + (ㅗㅜ 순환에서 ㅜ) + (ㅏㅓ 순환 새로 눌러 ㅏ 선택되지만 ㅜ+ㅏ→ㅝ 예외로 결합) → 뭐
    func testMwo() {
        let clock = NaratgulTestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.m, input: input, composer: composer, accumulated: &acc)
        press(.o, input: input, composer: composer, accumulated: &acc) // ㅗ
        press(.o, input: input, composer: composer, accumulated: &acc) // 재입력 → ㅜ
        press(.a, input: input, composer: composer, accumulated: &acc) // 새 키, ㅜ+ㅏ 예외 → ㅝ
        XCTAssertEqual(visible(acc, composer), "뭐")
    }

    // MARK: - 순환·획추가·쌍자음 단위 동작

    func testACycleTogglesBetweenAAndEoWithinWindow() {
        let clock = NaratgulTestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.a, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅏ")
        press(.a, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅓ")
        press(.a, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅏ")
    }

    func testACycleRestartsAfterWindowElapses() {
        let clock = NaratgulTestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.a, input: input, composer: composer, accumulated: &acc)
        press(.a, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅓ")
        clock.advance(by: 1600)
        press(.a, input: input, composer: composer, accumulated: &acc)
        // 창이 지나 새 자모로 취급되므로 앞의 "ㅓ"가 커밋되고 "ㅏ"가 새로 시작된다.
        XCTAssertEqual(visible(acc, composer), "ㅓㅏ")
    }

    func testOCycleTogglesBetweenOAndU() {
        let clock = NaratgulTestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.o, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅗ")
        press(.o, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅜ")
    }

    func testDoubleConsonantRoundTripOnGiyeok() {
        let clock = NaratgulTestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.g, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㄱ")
        press(.doubleConsonant, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㄲ")
        press(.doubleConsonant, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㄱ")
    }

    func testAddStrokeChainsSiotToJieutToChieut() {
        let clock = NaratgulTestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.s, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅅ")
        press(.addStroke, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅈ")
        press(.addStroke, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅊ")
    }

    func testAddStrokeOnUnmappedJamoIsNoOp() {
        let clock = NaratgulTestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.i, input: input, composer: composer, accumulated: &acc) // ㅣ는 획추가 표에 없다
        press(.addStroke, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅣ", "표에 없는 자모는 획추가가 아무 일도 하지 않아야 합니다")
    }

    func testDoubleConsonantOnUnmappedJamoIsNoOp() {
        let clock = NaratgulTestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.m, input: input, composer: composer, accumulated: &acc) // ㅁ은 쌍자음 표에 없다
        press(.doubleConsonant, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅁ", "표에 없는 자모는 쌍자음이 아무 일도 하지 않아야 합니다")
    }

    func testConsonantThenIGivesSimpleVowelCombination() {
        // ㅏ+ㅣ=ㅐ: HangulComposer의 compoundJung은 ㅗ/ㅜ/ㅡ 계열만 다뤄서 이 조합은 담당하지
        // 않는다. 나랏글 ㅣ 키가 천지인과 같은 iCombinations 표로 직접 결합해야 한다.
        let clock = NaratgulTestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.a, input: input, composer: composer, accumulated: &acc) // ㅏ
        press(.i, input: input, composer: composer, accumulated: &acc) // ㅣ → ㅐ(HangulComposer 결합)
        XCTAssertEqual(visible(acc, composer), "ㅐ")
    }

    /// ㅘ 위에서 ㅣ를 누르면 ㅙ로 결합돼야 한다. ㅘ 자체가 이미 겹모음이라 HangulComposer의
    /// jungHead가 한 번에(ㅘ→ㅗ) 한 단계만 되돌리므로 backspace가 2번 필요하다.
    func testWaThenIGivesWae() {
        let clock = NaratgulTestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.o, input: input, composer: composer, accumulated: &acc) // ㅗ
        press(.a, input: input, composer: composer, accumulated: &acc) // 새 키, ㅗ+ㅏ → ㅘ
        XCTAssertEqual(visible(acc, composer), "ㅘ")
        press(.i, input: input, composer: composer, accumulated: &acc) // ㅘ+ㅣ → ㅙ
        XCTAssertEqual(visible(acc, composer), "ㅙ")
    }

    /// ㅝ 위에서 ㅣ를 누르면 ㅞ로 결합돼야 한다(ㅝ도 겹모음이라 backspace 2번).
    func testWoThenIGivesWe() {
        let clock = NaratgulTestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.o, input: input, composer: composer, accumulated: &acc) // ㅗ
        press(.o, input: input, composer: composer, accumulated: &acc) // 재입력(창 안) → ㅜ
        press(.a, input: input, composer: composer, accumulated: &acc) // 새 키, ㅜ+ㅏ 예외 → ㅝ
        XCTAssertEqual(visible(acc, composer), "ㅝ")
        press(.i, input: input, composer: composer, accumulated: &acc) // ㅝ+ㅣ → ㅞ
        XCTAssertEqual(visible(acc, composer), "ㅞ")
    }

    // MARK: - space

    func testSpaceDuringMultiTapWindowEndsCycleWithoutSpace() {
        let clock = NaratgulTestClock()
        let input = makeInput(clock)
        _ = input.press(.a)
        let event = input.space()
        XCTAssertTrue(event.endsMultiTap)
        XCTAssertTrue(event.jamo.isEmpty)
    }

    func testSpaceAfterWindowSignalsCallerToInsertSpace() {
        let clock = NaratgulTestClock()
        let input = makeInput(clock)
        _ = input.press(.a)
        clock.advance(by: 1600)
        let event = input.space()
        XCTAssertFalse(event.endsMultiTap)
    }
}
