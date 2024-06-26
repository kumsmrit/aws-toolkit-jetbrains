// Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.core.telemetry

import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyCollection
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.stubbing.Answer
import software.amazon.awssdk.core.exception.SdkServiceException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TelemetryBatcherTest {
    private var publisher: TelemetryPublisher = mock()
    private var batcher = DefaultTelemetryBatcher(publisher, MAX_BATCH_SIZE, MAX_QUEUE_SIZE)

    @Before
    fun setUp() {
        batcher.onTelemetryEnabledChanged(true)
    }

    @Test
    fun testSingleBatch() {
        val publishCountDown = CountDownLatch(1)
        val publishCaptor = argumentCaptor<Collection<MetricEvent>>()

        publisher.stub {
            onBlocking { publisher.publish(publishCaptor.capture()) }
                .doAnswer(createPublishAnswer(publishCountDown))
        }

        batcher.enqueue(DefaultMetricEvent.builder().build())
        batcher.flush(false)

        waitForPublish(publishCountDown)

        verifyBlocking(publisher) { publish(anyCollection()) }

        assertThat(publishCaptor.firstValue).hasSize(1)
    }

    @Test
    fun testSplitBatch() {
        val publishCaptor = argumentCaptor<Collection<MetricEvent>>()

        val totalEvents = MAX_BATCH_SIZE + 1
        repeat(totalEvents) {
            batcher.enqueue(createEmptyMetricEvent())
        }
        batcher.flush(false)

        verifyBlocking(publisher, times(2)) { publish(publishCaptor.capture()) }

        assertThat(publishCaptor.allValues).hasSize(2)
        assertThat(publishCaptor.firstValue).hasSize(MAX_BATCH_SIZE)
        assertThat(publishCaptor.secondValue).hasSize(1)
    }

    @Test
    fun testRetryException() {
        val publishCaptor = argumentCaptor<Collection<MetricEvent>>()

        publisher.stub {
            onBlocking { publisher.publish(anyCollection()) }
                .doThrow(RuntimeException("Mock exception"))
        }

        batcher.enqueue(createEmptyMetricEvent())
        batcher.flush(true)

        verifyBlocking(publisher, times(1)) { publish(publishCaptor.capture()) }

        assertThat(publishCaptor.allValues).hasSize(1)
        assertThat(batcher.eventQueue).hasSize(1)
    }

    @Test
    fun testDontRetry400Exception() {
        val publishCaptor = argumentCaptor<Collection<MetricEvent>>()

        publisher.stub {
            onBlocking { publisher.publish(anyCollection()) }
                .doThrow(SdkServiceException.builder().statusCode(400).build())
                .doAnswer(Answer {})
        }

        batcher.enqueue(createEmptyMetricEvent())
        batcher.flush(true)

        verifyBlocking(publisher, times(1)) { publish(publishCaptor.capture()) }

        assertThat(publishCaptor.allValues).hasSize(1)
        assertThat(batcher.eventQueue).hasSize(0)
    }

    @Test
    fun testDispose() {
        val publishCaptor = argumentCaptor<Collection<MetricEvent>>()

        batcher.enqueue(createEmptyMetricEvent())
        batcher.shutdown()
        batcher.enqueue(createEmptyMetricEvent())
        batcher.shutdown()

        verifyBlocking(publisher) { publish(publishCaptor.capture()) }

        assertThat(publishCaptor.allValues).hasSize(1)
        assertThat(publishCaptor.firstValue.toList()).hasSize(1)
    }

    @Test
    fun testNotSendingWhileDisabled() {
        batcher.onTelemetryEnabledChanged(false)

        batcher.enqueue(createEmptyMetricEvent())
        batcher.flush(false)

        verifyNoMoreInteractions(publisher)

        assertThat(batcher.eventQueue).isEmpty()
    }

    @Test
    fun verifyOptOut() {
        batcher.enqueue(createEmptyMetricEvent())
        assertThat(batcher.eventQueue).isNotEmpty

        batcher.onTelemetryEnabledChanged(false)

        batcher.flush(false)

        verifyNoMoreInteractions(publisher)

        assertThat(batcher.eventQueue).isEmpty()
    }

    @Test
    fun verifyEnablementCallbackTrue() {
        val mockCallback: (Boolean) -> Unit = mock()

        batcher.onTelemetryEnabledChanged(true, mockCallback)
        verify(mockCallback, times(1)).invoke(true)
    }

    @Test
    fun verifyEnablementCallbackFalse() {
        val mockCallback: (Boolean) -> Unit = mock()

        batcher.onTelemetryEnabledChanged(false, mockCallback)
        verify(mockCallback, times(1)).invoke(false)
    }

    @Test
    fun verifyCallbackMetricPublished() {
        batcher.enqueue(createEmptyMetricEvent())
        val publishCountDown = CountDownLatch(1)
        val publishCaptor = argumentCaptor<Collection<MetricEvent>>()

        publisher.stub {
            onBlocking { publisher.publish(publishCaptor.capture()) }
                .doAnswer(createPublishAnswer(publishCountDown))
        }

        val fooMetricEvent = DefaultMetricEvent.builder()
            .createTime(Instant.now())
            .build()
        batcher.onTelemetryEnabledChanged(false) { batcher.enqueue(createEmptyMetricEvent()) }
        waitForPublish(publishCountDown)

        verifyBlocking(publisher) { publish(anyCollection()) }

        assertThat(publishCaptor.firstValue).hasSize(1)
        assertThat(publishCaptor.firstValue.contains(fooMetricEvent))
    }

    private fun createEmptyMetricEvent(): MetricEvent = DefaultMetricEvent.builder().build()

    private fun waitForPublish(publishCountDown: CountDownLatch) {
        // Wait for maximum of 5 secs before thread continues, may not reach final count though
        publishCountDown.await(5, TimeUnit.SECONDS)
    }

    private fun createPublishAnswer(publishCountDown: CountDownLatch): Answer<Unit> = Answer {
        publishCountDown.countDown()
    }

    companion object {
        private const val MAX_BATCH_SIZE = 5
        private const val MAX_QUEUE_SIZE = 10
    }
}
