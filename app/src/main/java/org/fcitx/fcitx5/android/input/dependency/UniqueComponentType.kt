/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.dependency

import org.mechdancer.dependency.IUniqueComponent
import org.mechdancer.dependency.UniqueComponent
import java.lang.reflect.ParameterizedType
import kotlin.reflect.KClass

/**
 * [IUniqueComponent]의 제네릭 인자 T를 Java 리플렉션으로 찾는다.
 *
 * 라이브러리의 `defaultType()`은 kotlin-reflect로 supertypes를 훑는다. 그 첫 호출이 kotlin-reflect
 * 초기화(내장 타입 로딩과 APK 매니페스트 검증)를 일으켜 키보드를 처음 띄울 때 메인 스레드를 2초 넘게 막았다.
 * 구체 클래스부터 상위로 올라가며 IUniqueComponent 계열로 매개변수화된 첫 상위 타입의 첫 인자를 쓰므로
 * `defaultType()`과 같은 클래스를 돌려준다. 찾지 못하면 `defaultType()`으로 넘긴다.
 */
fun IUniqueComponent<*>.uniqueComponentType(): KClass<out IUniqueComponent<*>> {
    var cls: Class<*>? = javaClass
    while (cls != null) {
        val supertypes = listOfNotNull(cls.genericSuperclass) + cls.genericInterfaces
        for (supertype in supertypes) {
            val parameterized = supertype as? ParameterizedType ?: continue
            val raw = parameterized.rawType as? Class<*> ?: continue
            if (!IUniqueComponent::class.java.isAssignableFrom(raw)) continue
            val argument = when (val arg = parameterized.actualTypeArguments.firstOrNull()) {
                is Class<*> -> arg
                is ParameterizedType -> arg.rawType as? Class<*>
                else -> null
            } ?: continue
            if (IUniqueComponent::class.java.isAssignableFrom(argument)) {
                @Suppress("UNCHECKED_CAST")
                return (argument as Class<out IUniqueComponent<*>>).kotlin
            }
        }
        cls = cls.superclass
    }
    return defaultType()
}

/** [UniqueComponent]와 같지만 타입을 [uniqueComponentType]으로 구해 kotlin-reflect 초기화를 피한다. */
abstract class AppUniqueComponent<T : AppUniqueComponent<T>> : UniqueComponent<T>() {
    override val type: KClass<out IUniqueComponent<*>> by lazy { uniqueComponentType() }
}
