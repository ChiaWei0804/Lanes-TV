package com.mytvb.feature.player.btr

import android.net.Uri
import com.mytvb.feature.player.VideoPlayerCdnFailoverState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BtrSchedulerPolicyTest {
    @Test
    fun measuredRouteIsPreferredAndEmptyFailuresAreTemporarilyExcluded() {
        val slow = Uri.parse("https://slow.bilivideo.com/video.m4s?x=1")
        val fast = Uri.parse("https://fast.bilivideo.com/video.m4s?x=1")
        val state = VideoPlayerCdnFailoverState(listOf(slow, fast))

        state.success(slow, 100_000L)
        state.success(fast, 2_000_000L)
        assertEquals(fast, state.rangeCandidates().first())

        state.failure(fast, 0L)
        state.failure(fast, 0L)
        assertTrue(state.rangeCandidates().none { it.host == "fast.bilivideo.com" })
    }

    @Test
    fun sortingSampledAndUnmeasuredRoutesDoesNotThrow() {
        // A cancelled hedge leaves a sampled metric without lastSuccessAt; unmeasured routes have none.
        val sampled = Uri.parse("https://sampled.bilivideo.com/video.m4s?x=1")
        val unmeasured = (1..6).map { Uri.parse("https://n$it.bilivideo.com/video.m4s?x=1") }
        val state = VideoPlayerCdnFailoverState(unmeasured + sampled)

        state.sample(sampled, 500_000L)

        assertEquals(sampled, state.rescueCandidates().first())
        assertEquals(sampled, state.rangeCandidates().first())
    }
}
