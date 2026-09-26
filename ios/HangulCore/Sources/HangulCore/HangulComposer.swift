public struct ComposerEvent: Equatable {
    public var commit: String
    public var preedit: String

    public init(commit: String, preedit: String) {
        self.commit = commit
        self.preedit = preedit
    }
}

public final class HangulComposer {
    private var cho: Int?
    private var jung: Int?
    private var jong: Int = 0
    /// 천지인 ㆍ 단독 상태(아직 다른 모음과 결합되지 않음). 실제 자모가 아니므로 조합에는
    /// 참여하지 않고 preedit 끝에만 잠깐 붙여 보여준다.
    private var dotPending = false

    public init() {}

    public var preedit: String {
        let base: String
        if let cho, let jung {
            base = String(Hangul.syllable(cho: cho, jung: jung, jong: jong))
        } else if let cho {
            base = String(Hangul.choseong[cho])
        } else if let jung {
            base = String(Hangul.jungseong[jung])
        } else {
            base = ""
        }
        return dotPending ? base + String(ChunjiinInput.dotCharacter) : base
    }

    public func process(jamo: Character) -> ComposerEvent {
        if jamo == ChunjiinInput.dotCharacter {
            dotPending = true
            return emit()
        }
        // 다음 자모가 들어오면 남아 있던 ㆍ 자리표시자는 자동으로 지운다(커밋하지 않는다).
        dotPending = false
        if let jungIndex = Hangul.jungseongIndex[jamo] {
            return processVowel(jungIndex)
        }
        if let choIndex = Hangul.choseongIndex[jamo] {
            return processConsonant(choIndex)
        }
        let flushed = flush()
        return ComposerEvent(commit: flushed.commit + String(jamo), preedit: "")
    }

    public func process(dubeolsikKey: Character, shift: Bool) -> ComposerEvent {
        guard let jamo = Dubeolsik.jamo(key: dubeolsikKey, shift: shift) else {
            let flushed = flush()
            return ComposerEvent(commit: flushed.commit + String(dubeolsikKey), preedit: "")
        }
        return process(jamo: jamo)
    }

    public func backspace() -> ComposerEvent {
        if dotPending {
            dotPending = false
            return emit()
        }
        if jong != 0 {
            if let split = Hangul.jongSplit[jong] {
                jong = split.remain
            } else {
                jong = 0
            }
        } else if let currentJung = jung {
            if let head = Hangul.jungHead[currentJung] {
                jung = head
            } else {
                jung = nil
            }
        } else if cho != nil {
            cho = nil
        }
        return emit()
    }

    public func flush() -> ComposerEvent {
        // ㆍ 자리표시자는 실제 자모가 아니므로 커밋하지 않고 버린다.
        dotPending = false
        let text = preedit
        reset()
        return ComposerEvent(commit: text, preedit: "")
    }

    public func reset() {
        cho = nil
        jung = nil
        jong = 0
        dotPending = false
    }

    private func emit(commit: String = "") -> ComposerEvent {
        ComposerEvent(commit: commit, preedit: preedit)
    }

    private func processVowel(_ incoming: Int) -> ComposerEvent {
        if let currentCho = cho, let currentJung = jung {
            if jong != 0 {
                if let split = Hangul.splitJong(jong) {
                    let outgoing = Hangul.syllable(cho: currentCho, jung: currentJung, jong: split.remain)
                    cho = split.cho
                    jung = incoming
                    jong = 0
                    return emit(commit: String(outgoing))
                }
                let outgoing = Hangul.syllable(cho: currentCho, jung: currentJung, jong: jong)
                cho = nil
                jung = incoming
                jong = 0
                return emit(commit: String(outgoing))
            }
            if let combined = Hangul.combineJung(current: currentJung, incoming: incoming) {
                jung = combined
                return emit()
            }
            let outgoing = Hangul.syllable(cho: currentCho, jung: currentJung)
            cho = nil
            jung = incoming
            jong = 0
            return emit(commit: String(outgoing))
        }
        if cho != nil {
            jung = incoming
            return emit()
        }
        if let currentJung = jung {
            if let combined = Hangul.combineJung(current: currentJung, incoming: incoming) {
                jung = combined
                return emit()
            }
            let outgoing = Hangul.jungseong[currentJung]
            jung = incoming
            return emit(commit: String(outgoing))
        }
        jung = incoming
        return emit()
    }

    private func processConsonant(_ incomingCho: Int) -> ComposerEvent {
        if let currentCho = cho, let currentJung = jung {
            if jong != 0 {
                if let incomingJong = Hangul.choToJong[incomingCho],
                   let combined = Hangul.combineJong(current: jong, incoming: incomingJong) {
                    jong = combined
                    return emit()
                }
                let outgoing = Hangul.syllable(cho: currentCho, jung: currentJung, jong: jong)
                cho = incomingCho
                jung = nil
                jong = 0
                return emit(commit: String(outgoing))
            }
            if let incomingJong = Hangul.choToJong[incomingCho] {
                jong = incomingJong
                return emit()
            }
            let outgoing = Hangul.syllable(cho: currentCho, jung: currentJung)
            cho = incomingCho
            jung = nil
            jong = 0
            return emit(commit: String(outgoing))
        }
        if let currentCho = cho {
            let outgoing = Hangul.choseong[currentCho]
            cho = incomingCho
            return emit(commit: String(outgoing))
        }
        if let currentJung = jung {
            let outgoing = Hangul.jungseong[currentJung]
            cho = incomingCho
            jung = nil
            return emit(commit: String(outgoing))
        }
        cho = incomingCho
        return emit()
    }
}
