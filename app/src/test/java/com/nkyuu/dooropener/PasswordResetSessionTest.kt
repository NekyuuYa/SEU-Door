package com.nkyuu.dooropener

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class PasswordResetSessionTest {
    private class QueueExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runNext() { tasks.removeFirst().run() }
    }

    @Test fun pendingSmsSurvivesObserverReplacementAndCannotBeSentTwice() {
        val worker = QueueExecutor()
        val completion = QueueExecutor()
        var sends = 0
        var remembered = ""
        var oldNotifications = 0
        var newNotifications = 0
        val session = PasswordResetSession(worker, completion, { sends++ }, { _, _, _ -> }, { remembered = it })
        val oldObserver: () -> Unit = { oldNotifications++ }
        val newObserver: () -> Unit = { newNotifications++ }
        session.attach(oldObserver)
        session.send("13800138000")
        session.detach(oldObserver)
        session.attach(newObserver)
        session.send("13800138000")
        assertEquals(1, worker.tasks.size)
        val oldCount = oldNotifications
        worker.runNext()
        completion.runNext()
        assertEquals(1, sends)
        assertEquals("13800138000", remembered)
        assertEquals(oldCount, oldNotifications)
        assertTrue(newNotifications > 0)
        assertEquals("Idle", session.state.name)
    }

    @Test fun successfulResetWithoutAnObserverIsDeliveredWhenTheUiReturns() {
        val worker = QueueExecutor()
        val completion = QueueExecutor()
        var resets = 0
        val session = PasswordResetSession(worker, completion, {}, { _, _, _ -> resets++ }, {})
        session.submit("13800138000", "123456", "024680")
        session.submit("13800138000", "123456", "024680")
        worker.runNext()
        completion.runNext()
        assertEquals(1, resets)
        assertEquals("Complete", session.state.name)
        var result = ""
        session.attach({ result = session.completedPassword })
        assertEquals("024680", result)
        assertEquals("13800138000", session.completedPhone)
    }

    @Test fun failedSmsReturnsToIdleWithoutStartingASuccessfulSendCooldown() {
        val worker = QueueExecutor()
        val completion = QueueExecutor()
        var remembered = false
        val rejection = IllegalStateException("Server rejected request")
        val session = PasswordResetSession(worker, completion, { throw rejection }, { _, _, _ -> }, { remembered = true })
        session.send("13800138000")
        worker.runNext()
        completion.runNext()
        assertEquals("Idle", session.state.name)
        assertSame(rejection, session.error)
        assertFalse(remembered)
    }
}
