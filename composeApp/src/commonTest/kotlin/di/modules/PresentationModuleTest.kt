package io.github.vrcmteam.vrcm.di.modules

import io.github.vrcmteam.vrcm.presentation.screens.avatar.AvatarProfileScreenModel
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.definition.Kind
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(KoinInternalApi::class)
class PresentationModuleTest {
    @Test
    fun avatarProfileScreenModelUsesFactoryScope() {
        val definition = presentationModule.mappings.values.single {
            it.beanDefinition.primaryType == AvatarProfileScreenModel::class
        }

        assertEquals(Kind.Factory, definition.beanDefinition.kind)
    }
}
