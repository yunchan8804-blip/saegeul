import XCTest
@testable import HangulCore

private final class DanmoumTestClock {
    var now = 0
    func advance(by ms: Int) { now += ms }
}

final class DanmoumGoldenTests: XCTestCase {
    private func makeInput(_ clock: DanmoumTestClock, windowMs: Int = 300) -> DanmoumInput {
        DanmoumInput(multiTapWindowMs: windowMs, now: { clock.now })
    }

    private func press(
        _ key: DanmoumKey, input: DanmoumInput, composer: HangulComposer, accumulated: inout String
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
        let clock = DanmoumTestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.ng, input: input, composer: composer, accumulated: &acc) // ㅇ
        press(.a, input: input, composer: composer, accumulated: &acc) // ㅏ → 아
        press(.n, input: input, composer: composer, accumulated: &acc) // ㄴ → 안
        press(.n, input: input, composer: composer, accumulated: &acc) // ㄴ(직접) → "안" 커밋, ㄴ 시작
        press(.eo, input: input, composer: composer, accumulated: &acc) // ㅓ
        press(.eo, input: input, composer: composer, accumulated: &acc) // 재입력(창 안) → ㅕ
        press(.ng, input: input, composer: composer, accumulated: &acc) // ㅇ → 녕
        XCTAssertEqual(visible(acc, composer), "안녕")
    }

    /// ㅐ를 연타하면 ㅒ가 된다("ㅒ는 ㅐ 연타"). ㅇ+ㅐㅐ(→ㅒ)로 "얘"를 만들고, 이어서 ㄱ+ㅣ로
    /// "기"를 붙인다. ㄱ이 일단 "얘"의 받침 후보로 들어갔다가 ㅣ가 오면서 다음 음절 초성으로
    /// 넘어가는 건 HangulComposer의 기존 겹받침 분리 로직이 그대로 처리한다.
    func testYaegi() {
        let clock = DanmoumTestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.ng, input: input, composer: composer, accumulated: &acc)
        press(.ae, input: input, composer: composer, accumulated: &acc) // ㅐ
        press(.ae, input: input, composer: composer, accumulated: &acc) // 재입력 → ㅒ
        press(.gk, input: input, composer: composer, accumulated: &acc) // ㄱ
        press(.i, input: input, composer: composer, accumulated: &acc) // ㅣ
        XCTAssertEqual(visible(acc, composer), "얘기")
    }

    // MARK: - 순환 단위 동작(전부 2단 왕복)

    func testBCycleTogglesBpAndBb() {
        let clock = DanmoumTestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.bp, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅂ")
        press(.bp, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅃ")
        press(.bp, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅂ", "2단 왕복이라 세 번째 탭은 다시 첫 후보여야 합니다")
    }

    func testACycleTogglesAAndYa() {
        let clock = DanmoumTestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.a, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅏ")
        press(.a, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅑ")
    }

    func testUCycleTogglesUAndYu() {
        let clock = DanmoumTestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.u, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅜ")
        press(.u, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅠ")
    }

    func testECycleTogglesEAndYe() {
        let clock = DanmoumTestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.e, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅔ")
        press(.e, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅖ")
    }

    func testOCycleTogglesOAndYo() {
        let clock = DanmoumTestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.o, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅗ")
        press(.o, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅛ")
    }

    func testCycleRestartsAfter300msWindowElapses() {
        let clock = DanmoumTestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.gk, input: input, composer: composer, accumulated: &acc)
        clock.advance(by: 350) // 300ms 창보다 길게
        press(.gk, input: input, composer: composer, accumulated: &acc)
        // 창이 지나 새 자모로 취급되므로 앞의 "ㄱ"이 커밋되고 새 "ㄱ"이 시작된다(ㄲ가 아니다).
        XCTAssertEqual(visible(acc, composer), "ㄱㄱ")
    }

    func testCycleWithinWindowReplacesInsteadOfCommitting() {
        let clock = DanmoumTestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.gk, input: input, composer: composer, accumulated: &acc)
        clock.advance(by: 250) // 300ms 창 안
        press(.gk, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㄲ", "창 안이면 커밋 없이 ㄲ으로 바뀌어야 합니다")
    }

    // MARK: - 직접 자음/모음(순환 없음)

    func testDirectConsonantsDoNotCycle() {
        let clock = DanmoumTestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.k, input: input, composer: composer, accumulated: &acc)
        press(.k, input: input, composer: composer, accumulated: &acc)
        // ㅋ는 직접 키라 연타해도 받침으로 겹쳐 붙지 않고 커밋 후 새로 시작한다(ㅋ은 겹받침 불가).
        XCTAssertEqual(visible(acc, composer), "ㅋㅋ")
    }

    // MARK: - space

    func testSpaceDuringMultiTapWindowEndsCycleWithoutSpace() {
        let clock = DanmoumTestClock()
        let input = makeInput(clock)
        _ = input.press(.gk)
        let event = input.space()
        XCTAssertTrue(event.endsMultiTap)
        XCTAssertTrue(event.jamo.isEmpty)
    }

    func testSpaceAfterWindowSignalsCallerToInsertSpace() {
        let clock = DanmoumTestClock()
        let input = makeInput(clock)
        _ = input.press(.gk)
        clock.advance(by: 350)
        let event = input.space()
        XCTAssertFalse(event.endsMultiTap)
    }
}
