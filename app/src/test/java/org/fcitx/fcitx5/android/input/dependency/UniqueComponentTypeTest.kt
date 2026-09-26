/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.dependency

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.mechdancer.dependency.IUniqueComponent

class UniqueComponentTypeTest {

    class Direct : AppUniqueComponent<Direct>()

    abstract class GenericBase<T : GenericBase<T>> : AppUniqueComponent<T>()
    class ViaGenericBase : GenericBase<ViaGenericBase>()

    open class OpenComponent : AppUniqueComponent<OpenComponent>()

    class FakeViewComponent : UniqueViewComponent<FakeViewComponent, View>() {
        override val view: View get() = throw UnsupportedOperationException()
    }

    private val samples: List<IUniqueComponent<*>> =
        listOf(Direct(), ViaGenericBase(), OpenComponent(), FakeViewComponent())

    @Test
    fun matchesLibraryDefaultTypeForEveryInheritancePattern() {
        for (component in samples) {
            assertEquals(component.javaClass.simpleName, component.defaultType(), component.uniqueComponentType())
        }
    }

    @Test
    fun distinctComponentsStayDistinct() {
        assertEquals(Direct(), Direct())
        assertNotEquals(Direct(), ViaGenericBase())
        assertEquals(FakeViewComponent::class, FakeViewComponent().type)
    }
}
