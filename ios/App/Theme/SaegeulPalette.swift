import SwiftUI
import UIKit

/// 온보딩 화면이 쓰는 색 토큰. 뷰는 이 토큰만 참조하고 하드코딩 색을 직접 쓰지 않는다.
enum SaegeulPalette {
    static let canvas = dynamicColor(light: 0xFBF7EF, dark: 0x101827)
    static let surface = dynamicColor(light: 0xFFFFFF, dark: 0x182238)
    static let ink = dynamicColor(light: 0x101827, dark: 0xFFF9ED)
    static let secondary = dynamicColor(light: 0x4B5563, dark: 0xB8C2CC)
    static let action = dynamicColor(light: 0x087A59, dark: 0x79F1C2)
    static let jadeFill = colorFromHex(0x55D6A6)
    static let hairline = dynamicColor(light: 0x101827, lightAlpha: 0.12, dark: 0xFFF9ED, darkAlpha: 0.16)

    private static func dynamicColor(light: UInt32, dark: UInt32) -> Color {
        Color(UIColor { traits in
            traits.userInterfaceStyle == .dark ? UIColor(saegeulHex: dark) : UIColor(saegeulHex: light)
        })
    }

    private static func dynamicColor(light: UInt32, lightAlpha: CGFloat, dark: UInt32, darkAlpha: CGFloat) -> Color {
        Color(UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(saegeulHex: dark).withAlphaComponent(darkAlpha)
                : UIColor(saegeulHex: light).withAlphaComponent(lightAlpha)
        })
    }

    private static func colorFromHex(_ hex: UInt32) -> Color {
        Color(UIColor(saegeulHex: hex))
    }
}

/// 체크리스트 화면의 여백 상수.
enum SaegeulSpacing {
    static let s4: CGFloat = 4
    static let s8: CGFloat = 8
    static let s12: CGFloat = 12
    static let s16: CGFloat = 16
    static let s24: CGFloat = 24
    static let s32: CGFloat = 32
}

/// 카드·버튼·도식의 모서리 반지름.
enum SaegeulRadius {
    static let card: CGFloat = 16
    static let button: CGFloat = 12
    static let illustration: CGFloat = 8
}

private extension UIColor {
    convenience init(saegeulHex hex: UInt32) {
        let red = CGFloat((hex >> 16) & 0xFF) / 255
        let green = CGFloat((hex >> 8) & 0xFF) / 255
        let blue = CGFloat(hex & 0xFF) / 255
        self.init(red: red, green: green, blue: blue, alpha: 1)
    }
}
