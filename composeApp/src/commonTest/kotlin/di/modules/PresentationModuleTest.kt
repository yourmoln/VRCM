package io.github.vrcmteam.vrcm.di.modules

import io.github.vrcmteam.vrcm.presentation.screens.avatar.AvatarProfileLoader
import io.github.vrcmteam.vrcm.presentation.screens.avatar.AvatarProfileScreenModel
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertNotSame

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
}
