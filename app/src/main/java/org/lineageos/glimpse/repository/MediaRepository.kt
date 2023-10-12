/*
 * SPDX-FileCopyrightText: 2023 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.repository

import android.content.Context
import org.lineageos.glimpse.flow.AlbumsFlow
import org.lineageos.glimpse.flow.LocationsFlow
import org.lineageos.glimpse.flow.MediaFlow

@Suppress("Unused")
object MediaRepository {
    fun media(context: Context, bucketId: Int) = MediaFlow(context, bucketId).flowData()
    fun mediaCursor(context: Context, bucketId: Int) = MediaFlow(context, bucketId).flowCursor()
    fun albums(context: Context) = AlbumsFlow(context).flowData()
    fun albumsCursor(context: Context) = AlbumsFlow(context).flowCursor()
    fun locations(context: Context) = LocationsFlow(context).get()
}
