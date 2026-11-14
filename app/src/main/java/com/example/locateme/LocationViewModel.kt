package com.example.locateme

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 界面状态。 */
data class UiState(
    val progress: LocateProgress = LocateProgress.Idle,
    val snapshot: LocationSnapshot? = null,
    val error: String? = null,
    val addressFailed: Boolean = false,
    val locationServiceEnabled: Boolean = true,
    val precision: Precision = Precision.NONE,
    /** 当前照片；拍照或选图后填充。 */
    val photo: PhotoInfo? = null,
    /** 照片正在加载/写 EXIF 时为 true，用于禁用按钮。 */
    val photoBusy: Boolean = false,
    /** 与照片相关的一次性提示。 */
    val photoMessage: String? = null,
)

class LocationViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = LocationRepository(application)
    private val photos = PhotoRepository(application)

    private val _state = MutableStateFlow(UiState(precision = repository.grantedPrecision()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 界面回到前台时刷新权限/开关状态。 */
    fun refreshCapabilities() {
        _state.value = _state.value.copy(
            precision = repository.grantedPrecision(),
            locationServiceEnabled = repository.isLocationEnabled(),
        )
    }

    fun locate() {
        refreshCapabilities()
        if (!repository.hasLocationPermission()) {
            _state.value = _state.value.copy(error = "请先授予定位权限")
            return
        }
        if (_state.value.progress != LocateProgress.Idle) return

        viewModelScope.launch {
            _state.value = _state.value.copy(error = null, addressFailed = false)
            val result = repository.locate { progress ->
                _state.value = _state.value.copy(progress = progress)
            }

            result.onFailure { e ->
                _state.value = _state.value.copy(
                    progress = LocateProgress.Idle,
                    error = e.message ?: "定位失败",
                )
            }
            result.onSuccess { location ->
                val approximate = repository.grantedPrecision() != Precision.FINE
                // 先展示经纬度，地址解析不阻塞 UI
                _state.value = _state.value.copy(
                    progress = LocateProgress.ResolvingAddress,
                    snapshot = LocationSnapshot.from(location, approximate),
                )
                val address = repository.reverseGeocode(location.latitude, location.longitude)
                val current = _state.value.snapshot
                _state.value = _state.value.copy(
                    progress = LocateProgress.Idle,
                    snapshot = current?.copy(address = address),
                    addressFailed = address == null,
                )
            }
        }
    }

    // ---------- 拍照 ----------

    /** CameraX 拍完回调：读 EXIF、写定位、生成缩略图。 */
    fun onPhotoCaptured(file: java.io.File) {
        viewModelScope.launch {
            _state.value = _state.value.copy(photoBusy = true, photoMessage = null)
            val info = photos.readPhoto(photos.uriForFile(file), PhotoSource.CAMERA)

            // 拍照时如果已有定位，顺手把坐标写进 EXIF，让照片本身携带位置
            val snapshot = _state.value.snapshot
            val tagged = if (snapshot != null) {
                photos.tagWithLocation(info.uri, snapshot)
            } else {
                false
            }

            _state.value = _state.value.copy(
                photoBusy = false,
                photo = info.copy(taggedWithLocation = tagged),
                photoMessage = when {
                    snapshot == null -> "照片已保存。先获取定位再拍照，可把坐标写进照片。"
                    tagged -> "已把当前坐标写入照片 EXIF"
                    else -> "照片已保存，但写入坐标失败"
                },
            )
        }
    }

    // ---------- 相册 ----------

    fun onPhotoPicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(photoBusy = true, photoMessage = null)
            val info = photos.readPhoto(uri, PhotoSource.GALLERY)
            _state.value = _state.value.copy(
                photoBusy = false,
                photo = info,
                photoMessage = if (info.thumbnail == null) "这张图片无法解码" else null,
            )
        }
    }

    fun clearPhoto() {
        _state.value = _state.value.copy(photo = null, photoMessage = null)
    }

    fun consumePhotoMessage() {
        _state.value = _state.value.copy(photoMessage = null)
    }
}
