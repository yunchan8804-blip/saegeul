import SwiftUI

/// 새글 iOS 호스트 앱의 첫 화면. 키보드 켜기 3단계(설정에서 추가 → 입력창에서 고르기 →
/// 가 입력)를 체크리스트로 보여주고, 놓친 단계를 자동으로 감지한다.
struct OnboardingChecklistView: View {
    let model: OnboardingModel

    @State private var probeText: String = ""
    @State private var isProbeFieldFocused: Bool = false
    @State private var expandedOverrides: [OnboardingModel.Step: Bool] = [:]
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GeometryReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: SaegeulSpacing.s12) {
                    header

                    AddKeyboardCard(
                        isDone: model.completed.contains(.addKeyboard),
                        isExpanded: isExpanded(.addKeyboard),
                        onToggle: { toggle(.addKeyboard) }
                    )

                    SelectKeyboardCard(
                        isDone: model.completed.contains(.selectKeyboard),
                        isExpanded: isExpanded(.selectKeyboard),
                        onToggle: { toggle(.selectKeyboard) },
                        onTryHere: { isProbeFieldFocused = true }
                    )

                    TypeGaCard(
                        isDone: model.completed.contains(.typeGa),
                        isExpanded: isExpanded(.typeGa),
                        onToggle: { toggle(.typeGa) },
                        probeText: $probeText,
                        isFieldFocused: $isProbeFieldFocused,
                        onInputMode: { model.noteInputMode(description: $0) }
                    )

                    ReassuranceCard()
                }
                .padding(.horizontal, SaegeulSpacing.s16)
                .padding(.vertical, SaegeulSpacing.s16)
            }
            .background(SaegeulPalette.canvas)
            // 스크롤을 위로 당겨 튕길(overscroll) 때 콘텐츠가 상태 바 뒤로 비치지 않도록,
            // 상태 바 높이만큼 캔버스색 불투명 막을 맨 위에 덧씌운다.
            .overlay(alignment: .top) {
                SaegeulPalette.canvas
                    .frame(height: proxy.safeAreaInsets.top)
                    .ignoresSafeArea(edges: .top)
            }
            .scrollDismissesKeyboard(.interactively)
            .onChange(of: probeText) { _, newValue in
                model.noteTyped(newValue)
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: SaegeulSpacing.s8) {
            headerTitle
            Text(model.progressText)
                .font(.subheadline)
                .foregroundStyle(SaegeulPalette.secondary)
            progressBar
        }
        .padding(.bottom, SaegeulSpacing.s8)
    }

    private var headerTitle: some View {
        Text(model.completed.count == OnboardingModel.Step.allCases.count ? "새글이 준비됐어요" : "새글")
            .font(.largeTitle.bold())
            .foregroundStyle(SaegeulPalette.ink)
            .overlay(alignment: .trailing) {
                Rectangle()
                    .fill(SaegeulPalette.jadeFill)
                    .frame(width: 3)
                    .offset(x: SaegeulSpacing.s8)
            }
    }

    private var progressBar: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(SaegeulPalette.hairline)
                Capsule()
                    .fill(SaegeulPalette.action)
                    .frame(width: proxy.size.width * progressFraction)
            }
        }
        .frame(height: 4)
        .animation(reduceMotion ? nil : .spring(duration: 0.35), value: progressFraction)
    }

    private var progressFraction: CGFloat {
        CGFloat(model.completed.count) / CGFloat(OnboardingModel.Step.allCases.count)
    }

    private func isExpanded(_ step: OnboardingModel.Step) -> Bool {
        expandedOverrides[step] ?? (step == model.currentStep)
    }

    private func toggle(_ step: OnboardingModel.Step) {
        let current = isExpanded(step)
        withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) {
            expandedOverrides[step] = !current
        }
    }
}

// MARK: - 공통 카드 부품

/// 배지(번호/체크) + 제목 + 설명. 완료일 때만 배지에 `onboarding.step.\(n).done` 식별자를 붙인다.
private struct StepCardHeader: View {
    let stepNumber: Int
    let title: String
    let description: String
    let isDone: Bool

    var body: some View {
        HStack(alignment: .top, spacing: SaegeulSpacing.s12) {
            badge
            VStack(alignment: .leading, spacing: SaegeulSpacing.s4) {
                Text(title)
                    .font(.headline)
                    .foregroundStyle(SaegeulPalette.ink)
                Text(description)
                    .font(.subheadline)
                    .foregroundStyle(SaegeulPalette.secondary)
            }
            Spacer(minLength: 0)
        }
        .contentShape(Rectangle())
    }

    private var badge: some View {
        let shape = ZStack {
            Circle().stroke(isDone ? SaegeulPalette.action : SaegeulPalette.hairline, lineWidth: 1.5)
            if isDone {
                Image(systemName: "checkmark")
                    .font(.footnote.bold())
                    .foregroundStyle(SaegeulPalette.action)
            } else {
                Text("\(stepNumber)")
                    .font(.footnote.bold())
                    .foregroundStyle(SaegeulPalette.secondary)
            }
        }
        .frame(width: 28, height: 28)

        return Group {
            if isDone {
                shape.accessibilityIdentifier("onboarding.step.\(stepNumber).done")
            } else {
                shape
            }
        }
    }
}

private extension View {
    /// 카드 공통 프레임: surface 배경, radius 16, hairline 1pt 테두리, 그림자 없음.
    func stepCardStyle() -> some View {
        self
            .padding(SaegeulSpacing.s16)
            .background(SaegeulPalette.surface)
            .clipShape(RoundedRectangle(cornerRadius: SaegeulRadius.card))
            .overlay(
                RoundedRectangle(cornerRadius: SaegeulRadius.card)
                    .stroke(SaegeulPalette.hairline, lineWidth: 1)
            )
    }
}

// MARK: - 1단계: 설정에서 새글 추가

private struct AddKeyboardCard: View {
    let isDone: Bool
    let isExpanded: Bool
    let onToggle: () -> Void
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(alignment: .leading, spacing: SaegeulSpacing.s12) {
            Button(action: onToggle) {
                StepCardHeader(
                    stepNumber: 1,
                    title: "설정에서 새글 추가",
                    description: "설정 › 새글 › 키보드에서 새글을 켜세요.",
                    isDone: isDone
                )
            }
            .buttonStyle(.plain)

            if isExpanded {
                SettingsPathIllustration()
                Text("직접 가려면: 설정 › 일반 › 키보드 › 키보드 › 새 키보드 추가…")
                    .font(.footnote)
                    .foregroundStyle(SaegeulPalette.secondary)
                Button("설정 열기") {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        openURL(url)
                    }
                }
                .buttonStyle(.borderedProminent)
                .tint(SaegeulPalette.action)
                .accessibilityIdentifier("onboarding.openSettings")
            }
        }
        .stepCardStyle()
    }
}

// MARK: - 2단계: 입력창에서 새글 고르기

private struct SelectKeyboardCard: View {
    let isDone: Bool
    let isExpanded: Bool
    let onToggle: () -> Void
    let onTryHere: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: SaegeulSpacing.s12) {
            Button(action: onToggle) {
                StepCardHeader(
                    stepNumber: 2,
                    title: "입력창에서 새글 고르기",
                    description: "키보드의 지구본 키를 길게 누르고 새글을 고르세요.",
                    isDone: isDone
                )
            }
            .buttonStyle(.plain)

            if isExpanded {
                GlobeKeyIllustration()
                Button("여기서 해보기", action: onTryHere)
                    .buttonStyle(.borderedProminent)
                    .tint(SaegeulPalette.action)
            }
        }
        .stepCardStyle()
    }
}

// MARK: - 3단계: 가 한 글자 입력해 보기

private struct TypeGaCard: View {
    let isDone: Bool
    let isExpanded: Bool
    let onToggle: () -> Void
    @Binding var probeText: String
    @Binding var isFieldFocused: Bool
    let onInputMode: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: SaegeulSpacing.s12) {
            Button(action: onToggle) {
                StepCardHeader(
                    stepNumber: 3,
                    title: "가 한 글자 입력해 보기",
                    description: "ㄱ 다음 ㅏ를 누르면 가가 됩니다.",
                    isDone: isDone
                )
            }
            .buttonStyle(.plain)

            if isExpanded {
                TypeGaIllustration()
            }

            // 펼침 여부와 무관하게 항상 표시한다. UI 테스트가 spikeTextField를 이 위치에서 찾는다.
            HangulProbeField(text: $probeText, isFocused: $isFieldFocused, onInputMode: onInputMode)
                .fixedSize(horizontal: false, vertical: true)
        }
        .stepCardStyle()
    }
}

// MARK: - 안심 카드

private struct ReassuranceCard: View {
    @State private var showsSecureExample = false

    var body: some View {
        VStack(alignment: .leading, spacing: SaegeulSpacing.s12) {
            HStack(spacing: SaegeulSpacing.s8) {
                Image(systemName: "lock.shield")
                    .foregroundStyle(SaegeulPalette.action)
                Text("전체 접근은 켜지 않아도 됩니다")
                    .font(.headline)
                    .foregroundStyle(SaegeulPalette.ink)
            }
            Text("새글은 네트워크를 쓰지 않습니다. 비밀번호 칸에서는 iOS가 시스템 키보드로 바꿉니다.")
                .font(.subheadline)
                .foregroundStyle(SaegeulPalette.secondary)
            // 비밀번호 칸을 이 화면에 두면 iOS가 같은 화면의 다른 입력칸에서도 서드파티 키보드를 숨긴다(실기기 실측 2026-09-14).
            // 그래서 예시는 별도 시트로 연다.
            Button("비밀번호 칸 예시 열기") {
                showsSecureExample = true
            }
            .buttonStyle(.bordered)
            .tint(SaegeulPalette.action)
            .accessibilityIdentifier("onboarding.openSecureExample")
        }
        .stepCardStyle()
        .sheet(isPresented: $showsSecureExample) {
            SecureExampleSheet()
                .presentationDetents([.medium])
        }
    }
}

private struct SecureExampleSheet: View {
    @State private var secureText: String = ""

    var body: some View {
        VStack(alignment: .leading, spacing: SaegeulSpacing.s16) {
            Text("비밀번호 칸 예시")
                .font(.headline)
                .foregroundStyle(SaegeulPalette.ink)
            Text("여기를 누르면 새글 대신 iOS 시스템 키보드가 뜹니다. 입력한 값은 어디에도 저장되지 않습니다.")
                .font(.subheadline)
                .foregroundStyle(SaegeulPalette.secondary)
            SecureField("비밀번호 칸 예시", text: $secureText)
                .textFieldStyle(.roundedBorder)
                .accessibilityIdentifier("spikeSecureField")
            Spacer()
        }
        .padding(SaegeulSpacing.s24)
        .background(SaegeulPalette.canvas)
    }
}
