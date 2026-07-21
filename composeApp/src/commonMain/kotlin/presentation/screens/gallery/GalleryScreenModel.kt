package io.github.vrcmteam.vrcm.presentation.screens.gallery

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import io.github.vrcmteam.vrcm.AppPlatform
import io.github.vrcmteam.vrcm.core.extensions.readFileBytes
import io.github.vrcmteam.vrcm.core.shared.SharedFlowCentre
import io.github.vrcmteam.vrcm.network.api.files.FileApi
import io.github.vrcmteam.vrcm.network.api.files.data.FileData
import io.github.vrcmteam.vrcm.network.api.files.data.FileTagType
import io.github.vrcmteam.vrcm.network.api.prints.PrintsApi
import io.github.vrcmteam.vrcm.network.api.prints.data.PrintData
import io.github.vrcmteam.vrcm.presentation.compoments.ToastText
import io.github.vrcmteam.vrcm.presentation.extensions.onApiFailure
import io.github.vrcmteam.vrcm.service.AuthService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import org.koin.core.logger.Logger

class GalleryScreenModel(
    private val authService: AuthService,
    private val fileApi: FileApi,
    private val printsApi: PrintsApi,
    private val logger: Logger,
    private val platform: AppPlatform,
) : ScreenModel {

    companion object {
        /** Gallery/Icon/Print 的上限 */
        const val MAX_FIXED_FILES = 64
        /** Emoji 默认上限 */
        const val MAX_EMOJI_DEFAULT = 32
        /** Sticker 默认上限 */
        const val MAX_STICKER_DEFAULT = 32
    }

    // 使用Map存储不同标签类型的图片文件列表
    private val _filesByTag = mutableStateMapOf<FileTagType, List<FileData>>().apply {
        // 不包含 Print，Print 使用独立的 API
        FileTagType.entries.filter { it != FileTagType.Print }.forEach { tagType ->
            this[tagType] = emptyList()
        }
    }

    // 使用Map存储不同标签类型的刷新状态
    private val _isRefreshingByTag = mutableStateMapOf<FileTagType, Boolean>().apply {
        FileTagType.entries.forEach { tagType ->
            this[tagType] = false
        }
    }

    // 拍立得使用独立的 API (GET /prints/user/{userId})
    private val _prints = mutableStateOf<List<PrintData>>(emptyList())
    private val _isRefreshingPrints = mutableStateOf(false)

    // 是否为 VRC+ 用户
    private val _isVrcPlus = mutableStateOf(false)
    val isVrcPlus: Boolean get() = _isVrcPlus.value

    fun init() {
        refreshAllFiles()
        screenModelScope.launch(Dispatchers.IO) {
            runCatching {
                _isVrcPlus.value = authService.currentUser().isSupporter
            }.onFailure {
                logger.error("Failed to fetch current user: $it")
            }
        }
    }

    private fun refreshAllFiles() {
        refreshFiles(FileTagType.Icon)
        refreshFiles(FileTagType.Emoji)
        refreshFiles(FileTagType.Sticker)
        refreshFiles(FileTagType.Gallery)
        refreshPrints()
    }

    /**
     * 根据标签类型刷新文件列表
     */
    fun refreshFiles(tagType: FileTagType, n: Int = 60, offset: Int = 0) {
        // 设置刷新状态为true
        _isRefreshingByTag[tagType] = true

        screenModelScope.launch(Dispatchers.IO) {
            authService.reTryAuthCatching { fileApi.getFiles(tagType, n = n, offset = offset) }
                .onGalleryFailure()
                .onSuccess { files ->
                    // 更新文件列表
                    _filesByTag[tagType] = files.sortedByDescending { file ->
                        file.versions.maxByOrNull { it.version }?.createdAt
                    }
                }
                .also {
                    // 设置刷新状态为false
                    _isRefreshingByTag[tagType] = false
                }
        }
    }

    /**
     * 获取拍立得列表（使用独立 API: GET /prints/user/{userId}）
     */
    fun refreshPrints(n: Int = 100, offset: Int = 0) {
        _isRefreshingPrints.value = true
        screenModelScope.launch(Dispatchers.IO) {
            val userId = authService.currentUser().id
            authService.reTryAuthCatching { printsApi.getUserPrints(userId, n = n, offset = offset) }
                .onGalleryFailure()
                .onSuccess { prints ->
                    _prints.value = prints.sortedByDescending { it.createdAt ?: it.timestamp }
                }
                .also {
                    _isRefreshingPrints.value = false
                }
        }
    }

    val prints: List<PrintData> get() = _prints.value

    val isRefreshingPrints: Boolean get() = _isRefreshingPrints.value

    /**
     * 根据标签类型获取对应的文件列表
     */
    fun getFilesByTag(tagType: FileTagType): List<FileData> {
        return _filesByTag[tagType] ?: emptyList()
    }

    /**
     * 根据标签类型获取对应的刷新状态
     */
    fun isRefreshingByTag(tagType: FileTagType): Boolean {
        return _isRefreshingByTag[tagType] ?: false
    }

    /**
     * 获取指定标签类型的当前文件数量
     */
    fun getFileCount(tagType: FileTagType): Int = when (tagType) {
        FileTagType.Print -> prints.size
        else -> getFilesByTag(tagType).size
    }

    /**
     * 获取指定标签类型的数量上限（参照 VRCX）
     */
    fun getMaxCount(tagType: FileTagType): Int = when (tagType) {
        FileTagType.Gallery, FileTagType.Icon, FileTagType.Print -> MAX_FIXED_FILES
        FileTagType.Emoji -> MAX_EMOJI_DEFAULT
        FileTagType.Sticker -> MAX_STICKER_DEFAULT
    }

    private inline fun <T> Result<T>.onGalleryFailure() =
        onApiFailure("Gallery") {
            logger.error(it)
            screenModelScope.launch {
                SharedFlowCentre.toastText.emit(ToastText.Error(it))
            }
        }

    /**
     * 上传图片到服务器
     * @param imagePath 图片文件路径
     */
    fun uploadImage(imagePath: String, fileTagType: FileTagType) {
        screenModelScope.launch(Dispatchers.IO) {
            try {
                // 通知用户正在上传
                SharedFlowCentre.toastText.emit(ToastText.Info("正在上传图片..."))

                // 读取文件字节
                val fileBytes = platform.readFileBytes(imagePath)

                // 获取文件名
                val fileName = imagePath.substringAfterLast('\\').substringAfterLast('/')

                // 获取MIME类型
                val mimeType = getMimeType(fileName)

                // 上传图片文件
                val result = fileApi.uploadImageFile(fileBytes, fileName, mimeType, fileTagType)

                result.onSuccess {
                    // 上传成功，刷新图片列表
                    SharedFlowCentre.toastText.emit(ToastText.Success("图片上传成功"))
                    refreshFiles(FileTagType.Gallery)
                }.onFailure {
                    // 上传失败
                    SharedFlowCentre.toastText.emit(ToastText.Error("图片上传失败: ${it.message}"))
                    logger.error("Upload failed: ${it.message}")
                }
            } catch (e: Exception) {
                // 处理异常
                SharedFlowCentre.toastText.emit(ToastText.Error("图片上传失败: ${e.message}"))
                logger.error("Upload exception: ${e.message}")
            }
        }
    }

    /**
     * 通过字节数组上传图片到指定标签类型
     * @param fileBytes 文件字节数组
     * @param fileName 文件名
     * @param tagType 文件标签类型
     * @param uploadingMessage 上传中提示
     * @param successMessage 上传成功提示
     * @param failedMessagePrefix 上传失败提示前缀
     */
    fun uploadImageBytes(
        fileBytes: ByteArray,
        fileName: String,
        tagType: FileTagType,
        uploadingMessage: String,
        successMessage: String,
        failedMessagePrefix: String,
    ) {
        screenModelScope.launch(Dispatchers.IO) {
            SharedFlowCentre.toastText.emit(ToastText.Info(uploadingMessage))
            val mimeType = getMimeType(fileName)
            val result = fileApi.uploadImageFile(fileBytes, fileName, mimeType, tagType)
            result.onSuccess {
                SharedFlowCentre.toastText.emit(ToastText.Success(successMessage))
                refreshFiles(tagType)
            }.onFailure {
                SharedFlowCentre.toastText.emit(
                    ToastText.Error("$failedMessagePrefix: ${it.message}")
                )
                logger.error("Upload failed: ${it.message}")
            }
        }
    }

    /**
     * 根据文件名获取MIME类型
     */
    private fun getMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".jpg", ignoreCase = true) || 
            fileName.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            fileName.endsWith(".png", ignoreCase = true) -> "image/png"
            fileName.endsWith(".gif", ignoreCase = true) -> "image/gif"
            fileName.endsWith(".bmp", ignoreCase = true) -> "image/bmp"
            else -> "application/octet-stream"
        }
    }
}
