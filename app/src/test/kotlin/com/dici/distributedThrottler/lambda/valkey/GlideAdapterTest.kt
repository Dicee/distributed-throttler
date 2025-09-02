package com.dici.distributedThrottler.lambda.valkey

import glide.api.GlideClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoSettings

@MockitoSettings
class GlideAdapterTest {
    @Mock private lateinit var glideClient: GlideClient
    @Mock private lateinit var passwordRefresher: IamPasswordRefresher

    private lateinit var glideAdapter: GlideAdapter

    @Nested
    inner class StaticPassword {
        @BeforeEach
        fun setUp() {
            glideAdapter = GlideAdapter(glideClient)
        }

        @Test
        fun testGlideClientGetter() {
            assertThat(glideAdapter.client()).isEqualTo(glideClient)

            verifyNoInteractions(glideClient)
        }
    }

    @Nested
    inner class DynamicPassword {
        @BeforeEach
        fun setUp() {
            glideAdapter = GlideAdapter(glideClient, passwordRefresher)
        }

        @Test
        fun testGlideClientGetter_doesNotNeedRefresh() {
            `when`(passwordRefresher.needsRefresh()).thenReturn(false)

            assertThat(glideAdapter.client()).isEqualTo(glideClient)

            verify(passwordRefresher, never()).refresh()
            verifyNoInteractions(glideClient)
        }

        @Test
        fun testGlideClientGetter_needsRefresh() {
            val newPassword = "new password"

            `when`(passwordRefresher.needsRefresh()).thenReturn(true)
            `when`(passwordRefresher.refresh()).thenReturn(newPassword)

            assertThat(glideAdapter.client()).isEqualTo(glideClient)

            verify(glideClient).updateConnectionPassword(newPassword, false)
            verifyNoMoreInteractions(glideClient)
        }
    }
}