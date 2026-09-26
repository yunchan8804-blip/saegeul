import XCTest
@testable import HangulCore

final class DubeolsikGoldenTests: XCTestCase {
    func testRaKaProducesGa() {
        let composer = HangulComposer()
        let afterR = composer.process(dubeolsikKey: "r", shift: false)
        XCTAssertEqual(afterR.commit, "")
        XCTAssertEqual(afterR.preedit, "ㄱ")

        let afterK = composer.process(dubeolsikKey: "k", shift: false)
        XCTAssertEqual(afterK.commit, "")
        XCTAssertEqual(afterK.preedit, "가")
        XCTAssertEqual(composer.preedit, "가")
    }

    func testRaKaSaProducesGan() {
        let composer = HangulComposer()
        _ = composer.process(dubeolsikKey: "r", shift: false)
        _ = composer.process(dubeolsikKey: "k", shift: false)
        let afterS = composer.process(dubeolsikKey: "s", shift: false)
        XCTAssertEqual(afterS.commit, "")
        XCTAssertEqual(afterS.preedit, "간")
        XCTAssertEqual(composer.preedit, "간")
    }

    func testDkssudFlushesAnnyeong() {
        let composer = HangulComposer()
        var accumulated = ""
        for key in "dkssud" {
            let event = composer.process(dubeolsikKey: key, shift: false)
            accumulated += event.commit
        }
        let flushed = composer.flush()
        accumulated += flushed.commit
        accumulated += flushed.preedit
        XCTAssertEqual(accumulated, "안녕")
        XCTAssertEqual(flushed.preedit, "")
    }

    func testBackspaceOnGaLeavesGiyeok() {
        let composer = HangulComposer()
        _ = composer.process(dubeolsikKey: "r", shift: false)
        _ = composer.process(dubeolsikKey: "k", shift: false)
        XCTAssertEqual(composer.preedit, "가")

        let event = composer.backspace()
        XCTAssertEqual(event.commit, "")
        XCTAssertEqual(event.preedit, "ㄱ")
        XCTAssertEqual(composer.preedit, "ㄱ")
    }

    func testFlushOnGaCommitsGa() {
        let composer = HangulComposer()
        _ = composer.process(dubeolsikKey: "r", shift: false)
        _ = composer.process(dubeolsikKey: "k", shift: false)
        let event = composer.flush()
        XCTAssertEqual(event.commit, "가")
        XCTAssertEqual(event.preedit, "")
        XCTAssertEqual(composer.preedit, "")
    }

    func testJungseongOnlyIsA() {
        let composer = HangulComposer()
        let event = composer.process(dubeolsikKey: "k", shift: false)
        XCTAssertEqual(event.commit, "")
        XCTAssertEqual(event.preedit, "ㅏ")
        XCTAssertEqual(composer.preedit, "ㅏ")
    }

    func testCompoundJungGwa() {
        let composer = HangulComposer()
        _ = composer.process(dubeolsikKey: "r", shift: false)
        _ = composer.process(dubeolsikKey: "h", shift: false)
        let event = composer.process(dubeolsikKey: "k", shift: false)
        XCTAssertEqual(event.commit, "")
        XCTAssertEqual(event.preedit, "과")
    }

    func testCompoundJongSplitsOntoNextSyllable() {
        let composer = HangulComposer()
        for key in "rkrt" {
            _ = composer.process(dubeolsikKey: key, shift: false)
        }
        XCTAssertEqual(composer.preedit, "갃")

        let event = composer.process(dubeolsikKey: "k", shift: false)
        XCTAssertEqual(event.commit, "각")
        XCTAssertEqual(event.preedit, "사")
    }

    func testShiftedSsProducesSsangSiotJong() {
        let composer = HangulComposer()
        _ = composer.process(dubeolsikKey: "r", shift: false)
        _ = composer.process(dubeolsikKey: "k", shift: false)
        let event = composer.process(dubeolsikKey: "t", shift: true)
        XCTAssertEqual(event.commit, "")
        XCTAssertEqual(event.preedit, "갔")
    }
}
