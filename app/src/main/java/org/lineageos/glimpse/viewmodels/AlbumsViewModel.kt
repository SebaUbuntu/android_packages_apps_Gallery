/*
 * SPDX-FileCopyrightText: 2023 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.lineageos.glimpse.ext.*
import org.lineageos.glimpse.repository.MediaRepository

class AlbumsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    val albums = MediaRepository.albums(context).flowOn(Dispatchers.IO).map {
        QueryResult.Data(it)
    }.stateIn(
        viewModelScope,
        started = SharingStarted.WhileSubscribed(),
        initialValue = QueryResult.Empty()
    )
}
