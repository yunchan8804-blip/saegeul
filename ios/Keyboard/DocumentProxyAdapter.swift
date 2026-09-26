import HangulCore
import UIKit

final class DocumentProxyAdapter {
    private let composer: HangulComposer
    private unowned let controller: UIInputViewController

    init(composer: HangulComposer, controller: UIInputViewController) {
        self.composer = composer
        self.controller = controller
    }

    private var proxy: UITextDocumentProxy {
        controller.textDocumentProxy
    }

    func apply(_ event: ComposerEvent) {
        if !event.commit.isEmpty {
            // 마킹 텍스트를 빈 문자열로 지운 뒤 확정 문자열을 일반 텍스트로 넣는다.
            // setMarkedText(commit)+unmarkText()는 실기기(iOS 26)에서 캐럿이 확정 문자열 앞에 남아 다음 입력이 앞에 끼었다.
            proxy.setMarkedText("", selectedRange: NSRange(location: 0, length: 0))
            if event.preedit.isEmpty {
                proxy.unmarkText()
            }
            // preedit가 곧바로 있으면(자음 재입력으로 이전 음절이 커밋되며 새 자음이 바로 마킹되는
            // 경우 등) unmarkText()로 조합 세션을 끝내지 않는다. host 쪽에서 unmarkText 직후 곧바로
            // 다시 setMarkedText하면 그 마킹이 자동으로 확정돼 버리는 현상을 실측으로 확인했다
            // ("안"+"ㄴ" 재입력 시퀀스에서 "ㄴ"이 커밋돼 "안ㄴ녕"이 되던 문제).
            proxy.insertText(event.commit)
        }
        if event.preedit.isEmpty {
            if event.commit.isEmpty {
                // 마킹만 남은 상태(예: 초성 하나)에서 preedit가 사라지면 마킹 텍스트를 빈 문자열로 치환한 뒤 확정한다.
                // unmarkText()만 부르면 iOS는 마킹 텍스트를 지우지 않고 문서에 확정한다.
                proxy.setMarkedText("", selectedRange: NSRange(location: 0, length: 0))
                proxy.unmarkText()
            }
        } else {
            let end = (event.preedit as NSString).length
            proxy.setMarkedText(event.preedit, selectedRange: NSRange(location: end, length: 0))
        }
    }

    func process(dubeolsikKey: Character, shift: Bool) {
        apply(composer.process(dubeolsikKey: dubeolsikKey, shift: shift))
    }

    /// 라틴·기호 문자 키 하나를 처리한다. 두벌식 키는 조합기로, 나머지는 그대로 삽입한다.
    func process(key: KeyDefinition, shiftState: ShiftState) {
        switch key.role {
        case .dubeolsik(let dubeolsikKey):
            process(dubeolsikKey: dubeolsikKey, shift: shiftState != .off)
        case .character:
            let text = (shiftState != .off ? key.shiftedLabel : nil) ?? key.label
            insertLiteral(text)
        case .chunjiin, .naratgul, .danmoum, .punctuationCycle, .shift, .backspace, .space, .enter, .layerToggle, .globe:
            break
        }
    }

    /// 라틴·기호 문자를 그대로 삽입한다. 조합 중이면 먼저 확정한다.
    func insertLiteral(_ text: String) {
        if !composer.preedit.isEmpty {
            flush()
        }
        proxy.insertText(text)
    }

    /// 롱프레스로 고른 대체 문자를 처리한다. 한글 층(두벌식 키)이면 자모로 조합기에 넣어
    /// 쌍자음 초성·ㅒㅖ 같은 겹모음이 조합에 그대로 참여하게 하고, 그 외(라틴 숫자 등)는 바로 삽입한다.
    func processAlternate(key: KeyDefinition, alternate: String) {
        switch key.role {
        case .dubeolsik:
            guard let jamo = alternate.first else { return }
            apply(composer.process(jamo: jamo))
        case .character:
            insertLiteral(alternate)
        case .chunjiin, .naratgul, .danmoum, .punctuationCycle, .shift, .backspace, .space, .enter, .layerToggle, .globe:
            break
        }
    }

    /// 천지인/나랏글/단모음 입력기(`ChunjiinInput`/`NaratgulInput`/`DanmoumInput`)가 공통으로
    /// 내는 `ChunjiinEvent`를 조합기에 그대로 적용한다: backspaces만큼 지우고 나온 자모를
    /// 순서대로 `process(jamo:)`에 넣는다. 이벤트는 jamo가 많아야 1개라 여러 커밋이 섞일 일은
    /// 없지만, 안전하게 전부 누적해서 한 번에 반영한다.
    func processMobileEvent(_ event: ChunjiinEvent) {
        var accumulatedCommit = ""
        var lastPreedit = composer.preedit
        for _ in 0..<event.backspaces {
            let result = composer.backspace()
            accumulatedCommit += result.commit
            lastPreedit = result.preedit
        }
        for jamo in event.jamo {
            let result = composer.process(jamo: jamo)
            accumulatedCommit += result.commit
            lastPreedit = result.preedit
        }
        guard event.backspaces > 0 || !event.jamo.isEmpty else { return }
        apply(ComposerEvent(commit: accumulatedCommit, preedit: lastPreedit))
    }

    /// 천지인 ".,?!" 멀티탭 키. 이전 탭을 대체하는 경우 그 문자를 지우고 다음 후보를 넣는다.
    func processPunctuationCycle(_ text: String, replacing: Bool) {
        if replacing {
            proxy.deleteBackward()
        }
        insertLiteral(text)
    }

    /// 스페이스 스와이프로 커서를 옮긴다. 조합 중이면 먼저 확정해 마킹 텍스트 위에서
    /// 커서를 옮기는 애매한 상태를 피한다.
    func moveCursor(byCharacterOffset offset: Int) {
        if !composer.preedit.isEmpty {
            flush()
        }
        proxy.adjustTextPosition(byCharacterOffset: offset)
    }

    func backspace() {
        if composer.preedit.isEmpty {
            proxy.deleteBackward()
        } else {
            apply(composer.backspace())
        }
    }

    func space() {
        apply(composer.flush())
        proxy.insertText(" ")
    }

    func newline() {
        apply(composer.flush())
        proxy.insertText("\n")
    }

    func flush() {
        apply(composer.flush())
    }
}
