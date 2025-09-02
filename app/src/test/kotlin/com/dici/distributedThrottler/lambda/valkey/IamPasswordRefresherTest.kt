package com.dici.distributedThrottler.lambda.valkey

import com.dici.distributedThrottler.lambda.valkey.auth.iam.IamAuthTokenGenerator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoSettings
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val INITIAL_INSTANT = Instant.parse("2025-01-01T01:02:03.00Z")
private const val INITIAL_PASSWORD = "1234"
private val EXPECTED_REFRESH_PERIOD = Duration.ofMinutes(10)

@MockitoSettings
class IamPasswordRefresherTest {
    private lateinit var passwordRefresher: IamPasswordRefresher

    @Mock private lateinit var authTokenGenerator: IamAuthTokenGenerator
    private lateinit var ticker: FakeTicker
    private lateinit var clock: Clock

    @BeforeEach
    fun setUp() {
        `when`(authTokenGenerator.getAuthToken()).thenReturn(INITIAL_PASSWORD)

        ticker = FakeTicker(INITIAL_INSTANT)
        clock = ticker.asClock()
        passwordRefresher = IamPasswordRefresher(authTokenGenerator, clock)
    }

    @Test
    fun testConstructor_initializesPassword() {
        assertThat(passwordRefresher.refresh()).isEqualTo(INITIAL_PASSWORD)

        verify(authTokenGenerator).getAuthToken() // called only once
    }

    @Test
    fun testNeedsRefresh_falseUntilExpiryTime() {
        ticker.advanceBy(EXPECTED_REFRESH_PERIOD.minusMillis(1))
        assertThat(passwordRefresher.needsRefresh()).isFalse

        ticker.advanceBy(Duration.ofMillis(1))
        assertThat(passwordRefresher.needsRefresh()).isTrue
    }

    @Test
    fun testNeedsRefresh_resetsAfterRefresh() {
        ticker.advanceBy(EXPECTED_REFRESH_PERIOD)
        assertThat(passwordRefresher.needsRefresh()).isTrue

        passwordRefresher.refresh()

        assertThat(passwordRefresher.needsRefresh()).isFalse

        ticker.advanceBy(EXPECTED_REFRESH_PERIOD.minusMillis(1))
        assertThat(passwordRefresher.needsRefresh()).isFalse

        ticker.advanceBy(Duration.ofMillis(1))
        assertThat(passwordRefresher.needsRefresh()).isTrue
    }

    @Test
    fun testRefresh_noopWhenNoRefreshIsNeeded() {
        assertThat(passwordRefresher.refresh()).isEqualTo(INITIAL_PASSWORD)

        ticker.advanceBy(EXPECTED_REFRESH_PERIOD.minusMillis(1))
        assertThat(passwordRefresher.refresh()).isEqualTo(INITIAL_PASSWORD)

        verify(authTokenGenerator).getAuthToken() // called only once
    }

    @Test
    fun testRefresh_fetchesNewPasswordUponExpiry() {
        assertThat(passwordRefresher.refresh()).isEqualTo(INITIAL_PASSWORD)

        val newPassword = "new password"
        `when`(authTokenGenerator.getAuthToken()).thenReturn(newPassword)

        ticker.advanceBy(EXPECTED_REFRESH_PERIOD)
        assertThat(passwordRefresher.refresh()).isEqualTo(newPassword)
    }
}