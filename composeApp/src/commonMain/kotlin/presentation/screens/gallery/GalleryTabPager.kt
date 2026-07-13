package io.github.vrcmteam.vrcm.presentation.screens.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.koin.koinScreenModel
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil3.CoilImage
import io.github.vrcmteam.vrcm.network.api.files.FileApi
import io.github.vrcmteam.vrcm.network.api.files.data.FileData
import io.github.vrcmteam.vrcm.network.api.files.data.FileTagType
import io.github.vrcmteam.vrcm.network.api.files.data.PrintData
import io.github.vrcmteam.vrcm.presentation.compoments.*
import io.github.vrcmteam.vrcm.presentation.settings.locale.strings
import io.github.vrcmteam.vrcm.presentation.supports.Pager
import org.koin.compose.koinInject

sealed class GalleryTabPager(private val tagType: FileTagType) : Pager {

    override val index: Int
        get() = tagType.ordinal

    override val title: String
        @Composable
        get() = tagType.toString().replaceFirstChar { it.uppercase() }

    override val icon: Painter?
        @Composable
        get() = null

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val galleryScreenModel: GalleryScreenModel = koinScreenModel()

        val isPrint = tagType == FileTagType.Print

        RefreshBox(
            isRefreshing = if (isPrint) galleryScreenModel.isRefreshingPrints else galleryScreenModel.isRefreshingByTag(tagType),
            doRefresh = { if (isPrint) galleryScreenModel.refreshPrints() else galleryScreenModel.refreshFiles(tagType) }
        ) {
            if (isPrint) {
                PrintContent(galleryScreenModel)
            } else {
                FileContent(galleryScreenModel)
            }
        }
    }

    @Composable
    private fun PrintContent(galleryScreenModel: GalleryScreenModel) {
        val prints = galleryScreenModel.prints
        if (prints.isEmpty() && !galleryScreenModel.isRefreshingPrints) {
            EmptyContent(message = strings.galleryTabNoFiles.replace("%s", title))
        } else {
            PrintGrid(prints)
        }
    }

    @Composable
    private fun FileContent(galleryScreenModel: GalleryScreenModel) {
        val files = galleryScreenModel.getFilesByTag(tagType)
        if (files.isEmpty() && !galleryScreenModel.isRefreshingByTag(tagType)) {
            EmptyContent(message = strings.galleryTabNoFiles.replace("%s", title))
        } else {
            GalleryGrid(files, tagType)
        }
    }

    /**
     * 拍立得网格展示
     */
    @Composable
    private fun PrintGrid(prints: List<PrintData>) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(
                items = prints,
                key = { it.id },
            ) { print ->
                PrintItem(print)
            }
        }
    }

    @Composable
    private fun PrintItem(print: PrintData) {
        val imageUrl = print.files?.image ?: ""
        val (dialogContent, setDialogContent) = LocationDialogContent.current

        Box(
            modifier = Modifier
                .fillMaxSize()
                .aspectRatio(16f / 9f),
        ) {
            AnimatedVisibility(dialogContent == null || (dialogContent as? ImagePreviewDialog)?.fileId != print.id) {
                CoilImage(
                    imageModel = { imageUrl },
                    imageOptions = ImageOptions(
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.Center
                    ),
                    imageLoader = { koinInject() },
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(MaterialTheme.shapes.medium)
                        .clickable {
                            setDialogContent(ImagePreviewDialog(print.id, print.id, ".png", imageUrl))
                        },
                    loading = {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    },
                    failure = {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = strings.galleryTabLoadFailed,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                )
            }
        }
    }

    @Composable
    private fun GalleryGrid(files: List<FileData>, tagType: FileTagType) {
        // 根据文件类型设置不同的列数
        val count = when (tagType) {
            FileTagType.Gallery, FileTagType.Print -> 2
            FileTagType.Emoji, FileTagType.Sticker -> 3
            FileTagType.Icon -> 4
        }
        // 根据文件类型设置不同的宽高比
        val aspectRatio = when (tagType) {
            FileTagType.Icon -> 1.0f  // 圆形展示，使用1:1比例
            FileTagType.Gallery, FileTagType.Print -> 16f / 9f  // 16:9比例
            FileTagType.Emoji, FileTagType.Sticker -> 1.0f  // 正方形展示，使用1:1比例
        }
        // 根据文件类型设置不同的形状
        val shape = when (tagType) {
            FileTagType.Icon -> CircleShape  // 圆形展示
            else -> MaterialTheme.shapes.medium  // 其他类型使用默认的medium形状
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(count),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(
                items = files,
                key = { it.id },
            ) { file ->
                GalleryItem(file, tagType, aspectRatio, shape)
            }
        }
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    @Composable
    private fun GalleryItem(
        file: FileData,
        tagType: FileTagType,
        aspectRatio: Float = 1f,
        shape: Shape = MaterialTheme.shapes.medium
    ) {
        val (dialogContent, setDialogContent) = LocationDialogContent.current



        Box(
            modifier = Modifier
                .fillMaxSize()
                .aspectRatio(aspectRatio),
        ) {
            // 获取最新版本的图片URL
            val latestVersion = file.versions.maxByOrNull { it.version }
            val imageUrl = if (latestVersion != null) {
                FileApi.convertFileUrl(file.id, 256)
            } else {
                ""
            }
            AnimatedVisibility(dialogContent == null || (dialogContent as ImagePreviewDialog).fileId != file.id) {
                CoilImage(
                    imageModel = { imageUrl },
                    imageOptions = ImageOptions(
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.Center
                    ),
                    imageLoader = { koinInject() },
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(shape)
                        .sharedBoundsBy(
                            file.id,
                            sharedTransitionScope = LocalSharedTransitionDialogScope.current,
                            animatedVisibilityScope = this@AnimatedVisibility
                        )
                        .clickable {
                            // 点击图片时，打开全屏预览
                            setDialogContent(ImagePreviewDialog(file.id, file.name, file.extension))
                        },
                    loading = {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    },
                    failure = {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = strings.galleryTabLoadFailed,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                )
            }

        }

    }

    companion object {
        data object Icon : GalleryTabPager(FileTagType.Icon)
        data object Emoji : GalleryTabPager(FileTagType.Emoji)
        data object Sticker : GalleryTabPager(FileTagType.Sticker)
        data object Gallery : GalleryTabPager(FileTagType.Gallery)

        data object Print : GalleryTabPager(FileTagType.Print)
    }
}
