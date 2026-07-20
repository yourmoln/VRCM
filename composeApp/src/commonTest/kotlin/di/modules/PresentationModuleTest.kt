package io.github.vrcmteam.vrcm.di.modules

import androidx.compose.ui.graphics.ImageBitmap
import io.github.vrcmteam.vrcm.presentation.screens.avatar.AvatarProfileLoader
import io.github.vrcmteam.vrcm.presentation.screens.avatar.AvatarProfileScreenModel
import io.github.vrcmteam.vrcm.presentation.screens.gallery.editor.DecodedImage
import io.github.vrcmteam.vrcm.presentation.screens.gallery.editor.PlatformImageCodec
import io.github.vrcmteam.vrcm.presentation.screens.gallery.editor.PrintImageProcessor
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertNotNull

class PresentationModuleTest {
    @Test
    fun avatarProfileScreenModelUsesFactoryScope() {
        val application = koinApplication {
            modules(
                presentationModule,
                module {
                    single<AvatarProfileLoader> {
                        AvatarProfileLoader { Result.failure(IllegalStateException("unused")) }
                    }
                },
            )
        }

        val first = application.koin.get<AvatarProfileScreenModel>()
        val second = application.koin.get<AvatarProfileScreenModel>()

        assertNotSame(first, second)
        application.close()
    }

    @Test
    fun printImageProcessorCanBeResolvedWithPlatformCodec() {
        val application = koinApplication {
            modules(
                presentationModule,
                module {
                    single<PlatformImageCodec> { FakePlatformImageCodec }
                },
            )
        }

        assertNotNull(application.koin.get<PrintImageProcessor>())
        application.close()
    }
}

private data object FakePlatformImageCodec : PlatformImageCodec {
    override suspend fun decode(bytes: ByteArray, maxDimension: Int): DecodedImage =
        error("unused")

    override suspend fun encodePng(bitmap: ImageBitmap): ByteArray = error("unused")
}
