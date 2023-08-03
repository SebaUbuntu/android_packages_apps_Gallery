/*
 * SPDX-FileCopyrightText: 2023 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ext

import android.view.View
import androidx.annotation.IdRes
import androidx.fragment.app.Fragment
import kotlin.reflect.KProperty

class FragmentViewProperty<T : View?>(
    private val fragment: Fragment, @IdRes private val viewId: Int
) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
        return fragment.requireView().findViewById<T>(viewId)
    }
}

fun <T : View?> Fragment.getViewProperty(@IdRes viewId: Int) =
    FragmentViewProperty<T>(this, viewId)
