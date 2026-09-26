import SwiftUI

/// 온보딩 카드 안에서 쓰는 도식 3개. SwiftUI Shape/Stack만으로 그리며
/// 상태바·시계 등 실제 스크린샷처럼 보이는 요소는 두지 않는다.

/// 설정 › 새글 › 키보드 경로를 세로 목록으로 보여주고, 마지막 행에 켜진 토글을 강조한다.
struct SettingsPathIllustration: View {
    private let rows: [(label: String, isFinal: Bool)] = [
        ("새글", false),
        ("키보드", false),
        ("새글", true)
    ]

    var body: some View {
        IllustrationCard {
            VStack(spacing: SaegeulSpacing.s4) {
                ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                    if index > 0 {
                        Text("›")
                            .font(.caption)
                            .foregroundStyle(SaegeulPalette.secondary)
                    }
                    HStack(spacing: SaegeulSpacing.s8) {
                        Text(row.label)
                            .font(.subheadline)
                            .foregroundStyle(SaegeulPalette.ink)
                        if row.isFinal {
                            Circle()
                                .fill(SaegeulPalette.jadeFill)
                                .frame(width: 10, height: 10)
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, SaegeulSpacing.s12)
                    .padding(.vertical, SaegeulSpacing.s4)
                    .frame(maxWidth: .infinity)
                    .background(SaegeulPalette.canvas)
                    .clipShape(RoundedRectangle(cornerRadius: SaegeulRadius.illustration / 2))
                }
            }
        }
    }
}

/// 미니 키보드 하단 줄에서 지구본 키를 강조하고, 길게 누르면 새글이 나온다는 말풍선을 보여준다.
/// 위쪽에 흐린 문자 키 두 줄을 더해 "키보드 맨 아랫줄의 지구본"이라는 맥락을 보여준다.
struct GlobeKeyIllustration: View {
    var body: some View {
        IllustrationCard {
            VStack(alignment: .leading, spacing: SaegeulSpacing.s4) {
                dimKeyRow(count: 10)
                dimKeyRow(count: 9)
                VStack(alignment: .leading, spacing: 0) {
                    calloutBubble
                    SpeechBubbleTail()
                        .fill(SaegeulPalette.canvas)
                        .frame(width: 12, height: 6)
                        .padding(.leading, SaegeulSpacing.s12)
                }
                HStack(spacing: SaegeulSpacing.s8) {
                    miniKey(systemImage: "globe", highlighted: true)
                    miniKeyBar(label: "스페이스")
                    miniKey(systemImage: "arrow.turn.down.left", highlighted: false)
                }
            }
        }
    }

    private func dimKeyRow(count: Int) -> some View {
        HStack(spacing: SaegeulSpacing.s4) {
            ForEach(0..<count, id: \.self) { _ in
                RoundedRectangle(cornerRadius: SaegeulRadius.illustration / 2)
                    .stroke(SaegeulPalette.hairline, lineWidth: 1)
                    .frame(height: 22)
            }
        }
    }

    private var calloutBubble: some View {
        Text("길게 누르기 → 새글")
            .font(.caption)
            .foregroundStyle(SaegeulPalette.ink)
            .padding(.horizontal, SaegeulSpacing.s8)
            .padding(.vertical, SaegeulSpacing.s4)
            .background(SaegeulPalette.canvas)
            .clipShape(RoundedRectangle(cornerRadius: SaegeulRadius.illustration / 2))
            .overlay(
                RoundedRectangle(cornerRadius: SaegeulRadius.illustration / 2)
                    .stroke(SaegeulPalette.hairline, lineWidth: 1)
            )
    }

    private func miniKey(systemImage: String, highlighted: Bool) -> some View {
        Image(systemName: systemImage)
            .font(.footnote)
            .foregroundStyle(highlighted ? Color.white : SaegeulPalette.ink)
            .frame(width: 36, height: 32)
            .background(highlighted ? SaegeulPalette.jadeFill : SaegeulPalette.canvas)
            .clipShape(RoundedRectangle(cornerRadius: SaegeulRadius.illustration / 2))
    }

    private func miniKeyBar(label: String) -> some View {
        Text(label)
            .font(.caption2)
            .foregroundStyle(SaegeulPalette.secondary)
            .frame(maxWidth: .infinity)
            .frame(height: 32)
            .background(SaegeulPalette.canvas)
            .clipShape(RoundedRectangle(cornerRadius: SaegeulRadius.illustration / 2))
    }
}

/// 미니 키보드 2줄에서 ㄱ·ㅏ 키만 강조하고, 위쪽 입력칸에 완성된 "가"를 보여준다.
struct TypeGaIllustration: View {
    private let topRow = ["ㅂ", "ㅈ", "ㄷ", "ㄱ", "ㅅ"]
    private let bottomRow = ["ㅁ", "ㄴ", "ㅇ", "ㄹ", "ㅎ", "ㅏ"]
    private let highlighted: Set<String> = ["ㄱ", "ㅏ"]

    var body: some View {
        IllustrationCard {
            VStack(spacing: SaegeulSpacing.s8) {
                inputBox
                keyRow(topRow)
                keyRow(bottomRow)
            }
        }
    }

    private var inputBox: some View {
        HStack {
            Text("가")
                .font(.headline)
                .foregroundStyle(SaegeulPalette.ink)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, SaegeulSpacing.s8)
        .padding(.vertical, SaegeulSpacing.s4)
        .frame(maxWidth: .infinity)
        .background(SaegeulPalette.canvas)
        .clipShape(RoundedRectangle(cornerRadius: SaegeulRadius.illustration / 2))
        .overlay(
            RoundedRectangle(cornerRadius: SaegeulRadius.illustration / 2)
                .stroke(SaegeulPalette.hairline, lineWidth: 1)
        )
    }

    private func keyRow(_ keys: [String]) -> some View {
        HStack(spacing: SaegeulSpacing.s4) {
            ForEach(keys, id: \.self) { key in
                Text(key)
                    .font(.footnote)
                    .foregroundStyle(highlighted.contains(key) ? Color.white : SaegeulPalette.ink)
                    .frame(maxWidth: .infinity)
                    .frame(height: 28)
                    .background(highlighted.contains(key) ? SaegeulPalette.jadeFill : SaegeulPalette.canvas)
                    .clipShape(RoundedRectangle(cornerRadius: SaegeulRadius.illustration / 2))
            }
        }
    }
}

/// 세 도식이 공유하는 카드 프레임: surface 배경, hairline 테두리, 높이 약 140pt.
private struct IllustrationCard<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        content
            .padding(.horizontal, SaegeulSpacing.s12)
            .padding(.vertical, SaegeulSpacing.s8)
            .frame(maxWidth: .infinity, minHeight: 140)
            .background(SaegeulPalette.surface)
            .clipShape(RoundedRectangle(cornerRadius: SaegeulRadius.illustration))
            .overlay(
                RoundedRectangle(cornerRadius: SaegeulRadius.illustration)
                    .stroke(SaegeulPalette.hairline, lineWidth: 1)
            )
    }
}

/// 말풍선 아래에 붙는 작은 삼각형 꼬리.
private struct SpeechBubbleTail: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: rect.minX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.width / 2, y: rect.maxY))
        path.closeSubpath()
        return path
    }
}
