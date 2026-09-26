import XCTest
@testable import HangulCore

final class KeyboardLayoutTests: XCTestCase {
    private let allLayers: [KeyboardLayer] = [
        .hangul, .latin, .symbols, .symbolsAlt, .chunjiin, .naratgul, .danmoum,
    ]

    func testAllLayersHaveFourRows() {
        for layer in allLayers {
            XCTAssertEqual(KeyboardLayouts.layout(for: layer).rows.count, 4, "\(layer) 층은 4행이어야 합니다")
        }
    }

    func testHangulLayoutHas26DubeolsikKeys() {
        let count = dubeolsikKeyCount(in: KeyboardLayouts.layout(for: .hangul))
        XCTAssertEqual(count, 26)
    }

    func testLatinLayoutHas26CharacterKeys() {
        let count = characterKeyCount(in: KeyboardLayouts.layout(for: .latin))
        XCTAssertEqual(count, 26)
    }

    func testSymbolsLayoutKeyCounts() {
        let layout = KeyboardLayouts.layout(for: .symbols)
        XCTAssertEqual(layout.rows[0].count, 10, "숫자 행은 10개여야 합니다")
        XCTAssertEqual(layout.rows[1].count, 10, "기호 2행은 10개여야 합니다")
        XCTAssertEqual(layout.rows[2].count, 7, "층 전환 + 기호 5개 + 백스페이스 = 7개여야 합니다")
        XCTAssertEqual(characterKeyCount(in: layout), 25, "숫자 10 + 기호 10 + 기호 5 = 25개 문자 키여야 합니다")
    }

    func testSymbolsAltLayoutKeyCounts() {
        let layout = KeyboardLayouts.layout(for: .symbolsAlt)
        XCTAssertEqual(layout.rows[0].count, 10)
        XCTAssertEqual(layout.rows[1].count, 10)
        XCTAssertEqual(layout.rows[2].count, 7)
        XCTAssertEqual(characterKeyCount(in: layout), 25, "기호 10 + 기호 10 + 기호 5 = 25개 문자 키여야 합니다")
    }

    func testEveryLayerHasALayerToggleKey() {
        for layer in allLayers {
            let layout = KeyboardLayouts.layout(for: layer)
            let hasToggle = layout.rows.flatMap { $0 }.contains {
                if case .layerToggle = $0.role { return true }
                return false
            }
            XCTAssertTrue(hasToggle, "\(layer) 층에는 층 전환 키가 있어야 합니다")
        }
    }

    func testHangulAndLatinRowThreeHaveShiftAndBackspace() {
        for layer: KeyboardLayer in [.hangul, .latin] {
            let row3 = KeyboardLayouts.layout(for: layer).rows[2]
            XCTAssertEqual(row3.first?.role, .shift, "\(layer) 3행 첫 키는 쉬프트여야 합니다")
            XCTAssertEqual(row3.last?.role, .backspace, "\(layer) 3행 마지막 키는 백스페이스여야 합니다")
        }
    }

    func testSymbolLayersRowThreeEndsWithBackspaceAndStartsWithToggle() {
        for layer: KeyboardLayer in [.symbols, .symbolsAlt] {
            let row3 = KeyboardLayouts.layout(for: layer).rows[2]
            XCTAssertEqual(row3.last?.role, .backspace)
            guard case .layerToggle = row3.first?.role else {
                return XCTFail("\(layer) 3행 첫 키는 층 전환 키여야 합니다")
            }
        }
    }

    func testHangulLayoutAlternatesForDoubleConsonantsAndCompoundVowels() {
        let row1 = KeyboardLayouts.layout(for: .hangul).rows[0]
        let expected: [Character: String] = [
            "q": "ㅃ", "w": "ㅉ", "e": "ㄸ", "r": "ㄲ", "t": "ㅆ", "o": "ㅒ", "p": "ㅖ",
        ]
        var found = 0
        for definition in row1 {
            guard case .dubeolsik(let key) = definition.role, let expectedAlt = expected[key] else { continue }
            XCTAssertEqual(definition.alternates, [expectedAlt], "\(key) 키의 대체 문자가 다릅니다")
            found += 1
        }
        XCTAssertEqual(found, expected.count, "대체 문자가 있어야 할 7개 키를 모두 찾지 못했습니다")
    }

    func testLatinLayoutRowOneHasNumberAlternates() {
        let row1 = KeyboardLayouts.layout(for: .latin).rows[0]
        let expectedDigits = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "0"]
        XCTAssertEqual(row1.count, 10)
        for (definition, digit) in zip(row1, expectedDigits) {
            XCTAssertEqual(definition.alternates, [digit], "\(definition.label) 키의 숫자 대체 문자가 \(digit)이어야 합니다")
        }
    }

    func testOtherLayersHaveNoAlternates() {
        for layer: KeyboardLayer in [.symbols, .symbolsAlt] {
            let hasAlternates = KeyboardLayouts.layout(for: layer).rows.flatMap { $0 }.contains { !$0.alternates.isEmpty }
            XCTAssertFalse(hasAlternates, "\(layer) 층에는 대체 문자가 없어야 합니다")
        }
    }

    func testChunjiinLayoutHasFourRowsOfFourEqualKeys() {
        let layout = KeyboardLayouts.layout(for: .chunjiin)
        XCTAssertEqual(layout.rows.count, 4)
        for (index, row) in layout.rows.enumerated() {
            // 4행은 맨 앞 지구본 키(needsInputModeSwitchKey일 때만 보임)가 더해져 5칸이다.
            let expectedCount = (index == 3) ? 5 : 4
            XCTAssertEqual(row.count, expectedCount, "천지인 \(index + 1)행 키 개수가 다릅니다")
        }
        XCTAssertEqual(layout.minKeyHeight, 52, "천지인은 열이 적어 키가 커야 합니다")
    }

    func testChunjiinLayoutHasGlobeKeyAtFrontOfRowFour() {
        let row4 = KeyboardLayouts.layout(for: .chunjiin).rows[3]
        XCTAssertEqual(row4.first?.role, .globe, "천지인 4행 맨 앞은 지구본 키여야 합니다")
        XCTAssertEqual(row4.first?.widthMultiplier, 0.8, "천지인 지구본 폭은 0.8배여야 합니다")
    }

    /// 지구본이 없을 때 왼쪽 3칸(한/영·ㅇㅁ·.,?!)이 문자 열(1.0배)과 겹치고, 스페이스는
    /// 채우기가 아니라 3행 기능열(123, 1.25배)과 같은 고정 폭이어야 한다.
    func testChunjiinLayoutRowFourAlignsWithCharacterAndFunctionColumns() {
        let row4 = KeyboardLayouts.layout(for: .chunjiin).rows[3]
        let contentKeys = row4.dropFirst() // 지구본 제외
        XCTAssertEqual(contentKeys.count, 4)
        for definition in contentKeys.dropLast() {
            XCTAssertEqual(definition.widthMultiplier, 1.0, "\(definition.label)은 문자 열과 같은 1.0배여야 합니다")
        }
        let space = contentKeys.last
        XCTAssertEqual(space?.role, .space)
        XCTAssertEqual(space?.widthMultiplier, 1.25, "스페이스는 3행 기능열(123)과 같은 1.25배 고정폭이어야 합니다")
    }

    func testChunjiinLayoutHasAllTenChunjiinKeys() {
        let expected: [ChunjiinKey] = [.i, .dot, .eu, .gk, .nr, .dt, .bp, .sh, .jc, .om]
        let found = KeyboardLayouts.layout(for: .chunjiin).rows.flatMap { $0 }.compactMap { definition -> ChunjiinKey? in
            if case .chunjiin(let key) = definition.role { return key }
            return nil
        }
        XCTAssertEqual(found.count, expected.count, "천지인 키 10개가 모두 있어야 합니다")
        for key in expected {
            XCTAssertTrue(found.contains(key), "\(key)가 천지인 배치에 없습니다")
        }
    }

    func testChunjiinLayoutHasBackspaceEnterAndPunctuationCycle() {
        let roles = KeyboardLayouts.layout(for: .chunjiin).rows.flatMap { $0 }.map(\.role)
        XCTAssertTrue(roles.contains(.backspace))
        XCTAssertTrue(roles.contains(.enter))
        XCTAssertTrue(roles.contains(.punctuationCycle([".", ",", "?", "!"])))
    }

    // MARK: - 나랏글

    func testNaratgulLayoutRowCounts() {
        let layout = KeyboardLayouts.layout(for: .naratgul)
        XCTAssertEqual(layout.rows.count, 4)
        // 1~3행은 나랏글 키 3개 + 기능 키 1개(지우기/줄바꿈/123) = 4칸.
        for index in 0...2 {
            XCTAssertEqual(layout.rows[index].count, 4, "나랏글 \(index + 1)행 키 개수가 다릅니다")
        }
        // 4행은 지구본·한/영·획추가·ㅡ·쌍자음·스페이스 = 6칸(needsInputModeSwitchKey일 때).
        XCTAssertEqual(layout.rows[3].count, 6, "나랏글 4행 키 개수가 다릅니다")
        XCTAssertEqual(layout.minKeyHeight, 52, "나랏글은 열이 적어 키가 커야 합니다")
    }

    func testNaratgulLayoutHasGlobeKeyAtFrontOfRowFour() {
        let row4 = KeyboardLayouts.layout(for: .naratgul).rows[3]
        XCTAssertEqual(row4.first?.role, .globe, "나랏글 4행 맨 앞은 지구본 키여야 합니다")
        XCTAssertEqual(row4.first?.widthMultiplier, 0.8, "나랏글 지구본 폭은 0.8배여야 합니다")
    }

    /// 지구본이 없을 때 왼쪽 칸(한/영·획추가·ㅡ·쌍자음)이 문자 열(1.0배)과 겹치고, 스페이스는
    /// 채우기가 아니라 3행 기능열(123, 1.25배)과 같은 고정 폭이어야 한다(천지인과 같은 규칙).
    func testNaratgulLayoutRowFourAlignsWithCharacterAndFunctionColumns() {
        let row4 = KeyboardLayouts.layout(for: .naratgul).rows[3]
        let contentKeys = row4.dropFirst() // 지구본 제외
        XCTAssertEqual(contentKeys.count, 5)
        for definition in contentKeys.dropLast() {
            XCTAssertEqual(definition.widthMultiplier, 1.0, "\(definition.label)은 문자 열과 같은 1.0배여야 합니다")
        }
        let space = contentKeys.last
        XCTAssertEqual(space?.role, .space)
        XCTAssertEqual(space?.widthMultiplier, 1.25, "스페이스는 3행 기능열(123)과 같은 1.25배 고정폭이어야 합니다")
        XCTAssertEqual(space?.label, "스페이스 ▾", "한글 자판이 4개라 스페이스에 자판 전환 표시가 있어야 합니다")
    }

    func testNaratgulLayoutHasAllTwelveNaratgulKeys() {
        let expected: [NaratgulKey] = [
            .g, .n, .r, .m, .s, .ng, .a, .o, .i, .eu, .addStroke, .doubleConsonant,
        ]
        let found = KeyboardLayouts.layout(for: .naratgul).rows.flatMap { $0 }.compactMap { definition -> NaratgulKey? in
            if case .naratgul(let key) = definition.role { return key }
            return nil
        }
        XCTAssertEqual(found.count, expected.count, "나랏글 키 12개가 모두 있어야 합니다")
        for key in expected {
            XCTAssertTrue(found.contains(key), "\(key)가 나랏글 배치에 없습니다")
        }
    }

    func testNaratgulLayoutHasBackspaceAndEnter() {
        let roles = KeyboardLayouts.layout(for: .naratgul).rows.flatMap { $0 }.map(\.role)
        XCTAssertTrue(roles.contains(.backspace))
        XCTAssertTrue(roles.contains(.enter))
    }

    // MARK: - 단모음

    func testDanmoumLayoutRowCounts() {
        let layout = KeyboardLayouts.layout(for: .danmoum)
        XCTAssertEqual(layout.rows.count, 4)
        XCTAssertEqual(layout.rows[0].count, 8, "단모음 1행은 8칸이어야 합니다")
        XCTAssertEqual(layout.rows[1].count, 8, "단모음 2행은 8칸이어야 합니다")
        XCTAssertEqual(layout.rows[2].count, 7, "단모음 3행은 자모 6개 + 지우기 = 7칸이어야 합니다")
        // 4행은 한글/라틴과 같은 모양: 지구본·한/영·123·스페이스·줄바꿈 = 5칸.
        XCTAssertEqual(layout.rows[3].count, 5, "단모음 4행 키 개수가 다릅니다")
        XCTAssertEqual(layout.minKeyHeight, 42, "단모음은 키가 작아 기본 높이를 그대로 써야 합니다")
    }

    func testDanmoumLayoutRowFourMatchesHangulShape() {
        let danmoumRow4 = KeyboardLayouts.layout(for: .danmoum).rows[3].map(\.role)
        let hangulRow4 = KeyboardLayouts.layout(for: .hangul).rows[3].map(\.role)
        XCTAssertEqual(danmoumRow4, hangulRow4, "단모음 4행은 한글 4행과 같은 모양이어야 합니다")
    }

    func testDanmoumLayoutHasAllTwentyTwoDanmoumKeys() {
        let expected: [DanmoumKey] = [
            .bp, .jc, .dt, .gk, .sh, .o, .ae, .e, .eo, .a, .u,
            .m, .n, .ng, .r, .h, .k, .t, .ch, .p, .i, .eu,
        ]
        let found = KeyboardLayouts.layout(for: .danmoum).rows.flatMap { $0 }.compactMap { definition -> DanmoumKey? in
            if case .danmoum(let key) = definition.role { return key }
            return nil
        }
        XCTAssertEqual(found.count, expected.count, "단모음 키 22개가 모두 있어야 합니다")
        for key in expected {
            XCTAssertTrue(found.contains(key), "\(key)가 단모음 배치에 없습니다")
        }
    }

    func testDanmoumLayoutHasBackspace() {
        let roles = KeyboardLayouts.layout(for: .danmoum).rows.flatMap { $0 }.map(\.role)
        XCTAssertTrue(roles.contains(.backspace))
    }

    // MARK: - 헬퍼

    private func dubeolsikKeyCount(in layout: KeyboardLayout) -> Int {
        layout.rows.flatMap { $0 }.filter {
            if case .dubeolsik = $0.role { return true }
            return false
        }.count
    }

    private func characterKeyCount(in layout: KeyboardLayout) -> Int {
        layout.rows.flatMap { $0 }.filter {
            if case .character = $0.role { return true }
            return false
        }.count
    }
}
