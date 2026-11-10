package com.kkorpse.less.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kkorpse.less.LessApp
import com.kkorpse.less.R
import com.kkorpse.less.data.MediaItem
import com.kkorpse.less.data.MediaRepository
import com.kkorpse.less.data.PhotoGroupBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PhotoHomeState(
    val loading: Boolean = true,
    val photos: List<MediaItem> = emptyList(),
    val error: String? = null
)

class PhotoHomeViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as LessApp
    private val builder = PhotoGroupBuilder(
        MediaRepository(application),
        app.preferences,
        app.browseStore
    )

    private val _state = MutableStateFlow(PhotoHomeState())
    val state = _state.asStateFlow()

    private var job: Job? = null

    init {
        load()
    }

    fun load(force: Boolean = false) {
        if (!force && job?.isActive == true) return
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            builder.newGroup()
                .onSuccess { photos ->
                    _state.value = PhotoHomeState(loading = false, photos = photos)
                }
                .onFailure {
                    _state.value = PhotoHomeState(
                        loading = false,
                        error = getApplication<Application>().getString(R.string.error_generic)
                    )
                }
        }
    }

    fun nextGroup() {
        load(force = true)
    }
}
