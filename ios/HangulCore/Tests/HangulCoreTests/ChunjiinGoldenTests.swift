import XCTest
@testable import HangulCore

/// 단조 밀리초 시계를 흉내 낸다. `ChunjiinInput`의 멀티탭 창 판정에 주입한다.
private final class TestClock {
    var now = 0
    func advance(by ms: Int) { now += ms }
}

final class ChunjiinGoldenTests: XCTestCase {
    private func makeInput(_ clock: TestClock, windowMs: Int = 1500) -> ChunjiinInput {
        ChunjiinInput(multiTapWindowMs: windowMs, now: { clock.now })
    }

    /// `DocumentProxyAdapter.processMobileEvent`와 동일하게: backspaces만큼 지우고 jamo를 순서대로 조합기에 넣는다.
    private func press(
        _ key: ChunjiinKey, input: ChunjiinInput, composer: HangulComposer, accumulated: inout String
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

    // MARK: - 자음 멀티탭

    func testConsonantCycleSecondPressWithinWindowGivesStrokeVariant() {
        let clock = TestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.gk, input: input, composer: composer, accumulated: &acc)
        press(.gk, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅋ")
    }

    func testConsonantCycleThirdPressWithinWindowGivesDoubleConsonant() {
        let clock = TestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.gk, input: input, composer: composer, accumulated: &acc)
        press(.gk, input: input, composer: composer, accumulated: &acc)
        press(.gk, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㄲ")
    }

    func testConsonantCycleShCyclesThroughShHAndSsangSiot() {
        let clock = TestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.sh, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅅ")
        press(.sh, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅎ")
        press(.sh, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅆ")
    }

    func testConsonantCycleAfterWindowElapsesStartsFresh() {
        let clock = TestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.gk, input: input, composer: composer, accumulated: &acc)
        clock.advance(by: 1600)
        press(.gk, input: input, composer: composer, accumulated: &acc)
        // 창이 지나 새 자모로 취급되므로 앞의 "ㄱ"이 커밋되고 새 "ㄱ"이 시작된다.
        XCTAssertEqual(visible(acc, composer), "ㄱㄱ")
    }

    // MARK: - ㆍ 누적 + 결합표

    func testDotThenIGivesEo() {
        let clock = TestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.dot, input: input, composer: composer, accumulated: &acc)
        press(.i, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅓ")
    }

    func testDotDotThenIGivesYeo() {
        let clock = TestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.dot, input: input, composer: composer, accumulated: &acc)
        press(.dot, input: input, composer: composer, accumulated: &acc)
        press(.i, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅕ")
    }

    func testDotThenEuGivesO() {
        let clock = TestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.dot, input: input, composer: composer, accumulated: &acc)
        press(.eu, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅗ")
    }

    func testDotDotThenEuGivesYo() {
        let clock = TestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.dot, input: input, composer: composer, accumulated: &acc)
        press(.dot, input: input, composer: composer, accumulated: &acc)
        press(.eu, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅛ")
    }

    func testEuThenDotGivesU() {
        let clock = TestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.eu, input: input, composer: composer, accumulated: &acc)
        press(.dot, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅜ")
    }

    func testEuThenDotDotGivesYu() {
        let clock = TestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.eu, input: input, composer: composer, accumulated: &acc)
        press(.dot, input: input, composer: composer, accumulated: &acc)
        press(.dot, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅠ")
    }

    func testIThenDotGivesA() {
        let clock = TestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.i, input: input, composer: composer, accumulated: &acc)
        press(.dot, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅏ")
    }

    func testIThenDotDotGivesYa() {
        let clock = TestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.i, input: input, composer: composer, accumulated: &acc)
        press(.dot, input: input, composer: composer, accumulated: &acc)
        press(.dot, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅑ")
    }

    func testRepeatedIWithoutDotDoesNotCombine() {
        let clock = TestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.i, input: input, composer: composer, accumulated: &acc)
        press(.i, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅣㅣ")
    }

    /// ㅗ 뒤 ㅣ는 ChunjiinInput 자체 결합표로 ㅚ가 되고, 그 뒤 ㆍ를 누르면(ㅚ→ㅘ) ㅗ까지 완전히
    /// 되돌려 ㅘ가 된다. 뒤의 네 겹모음(ㅘㅙㅝㅞ)은 HangulComposer의 겹모음 결합에 맡긴다는
    /// 설계 그대로, ChunjiinInput은 ㅚ→ㅘ 전환에서만 backspace 2회를 돌려준다
    /// (ㅚ는 그 자체가 겹모음이라 ㅗ까지 걷어내려면 HangulComposer.backspace를 두 번 불러야 한다).
    func testDotEuThenIDotGivesWa() {
        let clock = TestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.dot, input: input, composer: composer, accumulated: &acc)
        press(.eu, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅗ")
        press(.i, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅚ")
        press(.dot, input: input, composer: composer, accumulated: &acc)
        XCTAssertEqual(visible(acc, composer), "ㅘ")
    }

    /// ㅘ 위에서 ㅣ를 또 누르면 ㅙ로 결합돼야 한다(Android `chunjiinICombinations`의 "ㅘ"→"ㅙ").
    func testWaThenIGivesWae() {
        let clock = TestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.dot, input: input, composer: composer, accumulated: &acc)
        press(.eu, input: input, composer: composer, accumulated: &acc) // ㅗ
        press(.i, input: input, composer: composer, accumulated: &acc) // ㅚ
        press(.dot, input: input, composer: composer, accumulated: &acc) // ㅘ
        XCTAssertEqual(visible(acc, composer), "ㅘ")
        press(.i, input: input, composer: composer, accumulated: &acc) // ㅙ
        XCTAssertEqual(visible(acc, composer), "ㅙ")
    }

    /// ㅠ 위에서 ㅣ를 누르면 ㅝ로 결합돼야 한다(Android `chunjiinICombinations`의 "ㅠ"→"ㅝ").
    func testYuThenIGivesWo() {
        let clock = TestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.eu, input: input, composer: composer, accumulated: &acc) // ㅡ
        press(.dot, input: input, composer: composer, accumulated: &acc) // ㅜ
        press(.dot, input: input, composer: composer, accumulated: &acc) // ㅠ
        XCTAssertEqual(visible(acc, composer), "ㅠ")
        press(.i, input: input, composer: composer, accumulated: &acc) // ㅝ
        XCTAssertEqual(visible(acc, composer), "ㅝ")
    }

    // MARK: - 자음 + 모음 통합

    func testConsonantThenCombinedVowelGivesSyllable() {
        let clock = TestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.nr, input: input, composer: composer, accumulated: &acc) // ㄴ
        press(.dot, input: input, composer: composer, accumulated: &acc) // ㆍ
        press(.eu, input: input, composer: composer, accumulated: &acc) // ㅡ → ㅗ 결합 → 노
        XCTAssertEqual(visible(acc, composer), "노")
    }

    func testFullWordAnnyeong() {
        let clock = TestClock()
        let input = makeInput(clock)
        let composer = HangulComposer()
        var acc = ""
        press(.om, input: input, composer: composer, accumulated: &acc) // ㅇ
        press(.i, input: input, composer: composer, accumulated: &acc) // ㅣ → 이
        press(.dot, input: input, composer: composer, accumulated: &acc) // ㆍ → 아
        press(.nr, input: input, composer: composer, accumulated: &acc) // ㄴ → 안
        clock.advance(by: 1600) // 멀티탭 창 경과: 다음 ㄴ은 새 자모다
        press(.nr, input: input, composer: composer, accumulated: &acc) // ㄴ → "안" 커밋, ㄴ 시작
        press(.dot, input: input, composer: composer, accumulated: &acc) // ㆍ
        press(.dot, input: input, composer: composer, accumulated: &acc) // ㆍㆍ
        press(.i, input: input, composer: composer, accumulated: &acc) // ㅣ → 녀
        press(.om, input: input, composer: composer, accumulated: &acc) // ㅇ → 녕
        XCTAssertEqual(visible(acc, composer), "안녕")
    }

    // MARK: - space

    func testSpaceDuringMultiTapWindowEndsCycleWithoutSpace() {
        let clock = TestClock()
        let input = makeInput(clock)
        _ = input.press(.gk)
        let event = input.space()
        XCTAssertTrue(event.endsMultiTap, "멀티탭 창 안에서는 순환만 닫아야 합니다")
        XCTAssertTrue(event.jamo.isEmpty, "창을 닫을 때는 자모를 내보내지 않아야 합니다")
        XCTAssertEqual(event.backspaces, 0)
    }

    func testSpaceAfterWindowSignalsCallerToInsertSpace() {
        let clock = TestClock()
        let input = makeInput(clock)
        _ = input.press(.gk)
        clock.advance(by: 1600)
        let event = input.space()
        XCTAssertFalse(event.endsMultiTap, "창 밖이면 평소처럼 공백을 입력해야 합니다")
        XCTAssertTrue(event.jamo.isEmpty)
    }

    // MARK: - HangulComposer의 ㆍ 자리표시자(dotPending) 골든

    func testHangulComposerShowsDotAlonePreedit() {
        let composer = HangulComposer()
        let event = composer.process(jamo: ChunjiinInput.dotCharacter)
        XCTAssertEqual(event.commit, "")
        XCTAssertEqual(event.preedit, "\u{318D}")
        XCTAssertEqual(composer.preedit, "\u{318D}")
    }

    func testHangulComposerBackspaceOnDotAloneClearsPreedit() {
        let composer = HangulComposer()
        _ = composer.process(jamo: ChunjiinInput.dotCharacter)
        let event = composer.backspace()
        XCTAssertEqual(event.commit, "")
        XCTAssertEqual(event.preedit, "")
        XCTAssertEqual(composer.preedit, "")
    }
}
