/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.namecard

import dev.superdrop.namecard.NameCardConsentMachine.Effect
import dev.superdrop.namecard.NameCardConsentMachine.Event
import dev.superdrop.namecard.NameCardConsentMachine.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive pure-JVM tests for [NameCardConsentMachine]: all 9 §3 matrix cells, both event
 * orderings (local-first vs peer-first), card-arrival timing permutations (plan D4), both timeout
 * rows, early disconnect, the resolved-but-card-failed edge, and post-terminal / duplicate guards.
 */
class NameCardConsentMachineTest {
    /** Run [events] on a fresh machine; return (all effects concatenated in order, final state). */
    private fun run(vararg events: Event): Pair<List<Effect>, State> {
        val m = NameCardConsentMachine()
        val effects = mutableListOf<Effect>()
        for (e in events) effects += m.onEvent(e)
        return effects to m.state
    }

    private fun share(v: Boolean) = Effect.SendChoice(v)

    // ---- 9 matrix cells, local-choice-first ordering ----

    @Test
    fun `both share - mutual - local first`() {
        val (fx, state) = run(Event.LocalShare, Event.PeerShare, Event.PeerCardArrived)
        assertEquals(
            listOf(
                share(true),
                Effect.TransmitCard,
                Effect.ShowHeadsUpWaiting,
                Effect.SaveCardAndRipple,
                Effect.CloseLink,
            ),
            fx,
        )
        assertEquals(State.DONE_MUTUAL, state)
    }

    @Test
    fun `share vs receive-only - peer declines - local first`() {
        val (fx, state) = run(Event.LocalShare, Event.PeerReceiveOnly)
        assertEquals(
            listOf(
                share(true),
                Effect.TransmitCard,
                Effect.ShowHeadsUpWaiting,
                Effect.UpdateHeadsUpDeclined,
                Effect.CloseLink,
            ),
            fx,
        )
        assertEquals(State.DONE_SHARED_PEER_DECLINED, state)
    }

    @Test
    fun `share then no response - timeout`() {
        val (fx, state) = run(Event.LocalShare, Event.Timeout)
        assertEquals(
            listOf(
                share(true),
                Effect.TransmitCard,
                Effect.ShowHeadsUpWaiting,
                Effect.ShowNoResponse,
                Effect.CloseLink,
            ),
            fx,
        )
        assertEquals(State.DONE_NO_RESPONSE, state)
    }

    @Test
    fun `receive-only vs share - I save theirs - local first`() {
        val (fx, state) = run(Event.LocalReceiveOnly, Event.PeerShare, Event.PeerCardArrived)
        assertEquals(
            listOf(share(false), Effect.ShowWaiting, Effect.SaveCardAndRipple, Effect.CloseLink),
            fx,
        )
        assertEquals(State.DONE_RECEIVED_PEER_SHARED, state)
    }

    @Test
    fun `both receive-only - fade to declined - local first`() {
        val (fx, state) = run(Event.LocalReceiveOnly, Event.PeerReceiveOnly)
        assertEquals(
            listOf(share(false), Effect.ShowWaiting, Effect.FadeToDeclined, Effect.CloseLink),
            fx,
        )
        assertEquals(State.DONE_BOTH_DECLINED, state)
    }

    @Test
    fun `receive-only then no response - timeout`() {
        val (fx, state) = run(Event.LocalReceiveOnly, Event.Timeout)
        assertEquals(
            listOf(share(false), Effect.ShowWaiting, Effect.ShowNoResponse, Effect.CloseLink),
            fx,
        )
        assertEquals(State.DONE_NO_RESPONSE, state)
    }

    // ---- the three "I never chose" cells (peer acts, I time out) ----

    @Test
    fun `peer shares, I never choose - card saved then timeout`() {
        val (fx, state) = run(Event.PeerShare, Event.PeerCardArrived, Event.Timeout)
        assertEquals(listOf(Effect.SaveCardAndRipple, Effect.ShowNoResponse, Effect.CloseLink), fx)
        assertEquals(State.DONE_NO_RESPONSE, state)
    }

    @Test
    fun `peer declines, I never choose - timeout`() {
        val (fx, state) = run(Event.PeerReceiveOnly, Event.Timeout)
        assertEquals(listOf(Effect.ShowNoResponse, Effect.CloseLink), fx)
        assertEquals(State.DONE_NO_RESPONSE, state)
    }

    @Test
    fun `nobody chooses - timeout`() {
        val (fx, state) = run(Event.Timeout)
        assertEquals(listOf(Effect.ShowNoResponse, Effect.CloseLink), fx)
        assertEquals(State.DONE_NO_RESPONSE, state)
    }

    // ---- ordering permutations: peer-first, and card before the local choice ----

    @Test
    fun `both share - peer first, card between - no headsup`() {
        val (fx, state) = run(Event.PeerShare, Event.PeerCardArrived, Event.LocalShare)
        assertEquals(listOf(Effect.SaveCardAndRipple, share(true), Effect.TransmitCard, Effect.CloseLink), fx)
        assertEquals(State.DONE_MUTUAL, state)
    }

    @Test
    fun `share vs receive-only - peer first - no headsup wait`() {
        val (fx, state) = run(Event.PeerReceiveOnly, Event.LocalShare)
        assertEquals(listOf(share(true), Effect.TransmitCard, Effect.UpdateHeadsUpDeclined, Effect.CloseLink), fx)
        assertEquals(State.DONE_SHARED_PEER_DECLINED, state)
    }

    @Test
    fun `receive-only vs share - peer first, card before my choice`() {
        val (fx, state) = run(Event.PeerShare, Event.PeerCardArrived, Event.LocalReceiveOnly)
        assertEquals(listOf(Effect.SaveCardAndRipple, share(false), Effect.CloseLink), fx)
        assertEquals(State.DONE_RECEIVED_PEER_SHARED, state)
    }

    @Test
    fun `both receive-only - peer first`() {
        val (fx, state) = run(Event.PeerReceiveOnly, Event.LocalReceiveOnly)
        assertEquals(listOf(share(false), Effect.FadeToDeclined, Effect.CloseLink), fx)
        assertEquals(State.DONE_BOTH_DECLINED, state)
    }

    @Test
    fun `both share - local and peer choose before card, card closes`() {
        // Choices resolve first (CloseLink deferred), then the card arrives and closes.
        val (fx, state) = run(Event.LocalShare, Event.PeerShare, Event.PeerCardArrived)
        assertTrue("CloseLink must be last (after the card)", fx.last() == Effect.CloseLink)
        assertEquals(Effect.SaveCardAndRipple, fx[fx.size - 2])
        assertEquals(State.DONE_MUTUAL, state)
    }

    // ---- edges ----

    @Test
    fun `disconnect before any choice - no response`() {
        val (fx, state) = run(Event.Disconnected)
        assertEquals(listOf(Effect.ShowNoResponse, Effect.CloseLink), fx)
        assertEquals(State.DONE_NO_RESPONSE, state)
    }

    @Test
    fun `disconnect after I shared, peer silent - no response`() {
        val (fx, state) = run(Event.LocalShare, Event.Disconnected)
        assertEquals(
            listOf(
                share(true),
                Effect.TransmitCard,
                Effect.ShowHeadsUpWaiting,
                Effect.ShowNoResponse,
                Effect.CloseLink,
            ),
            fx,
        )
        assertEquals(State.DONE_NO_RESPONSE, state)
    }

    @Test
    fun `mutual resolved but peer card never arrives - timeout just closes, stays mutual`() {
        val (fx, state) = run(Event.LocalShare, Event.PeerShare, Event.Timeout)
        assertEquals(
            listOf(share(true), Effect.TransmitCard, Effect.ShowHeadsUpWaiting, Effect.CloseLink),
            fx,
        )
        assertEquals(State.DONE_MUTUAL, state)
    }

    @Test
    fun `events after terminal are ignored`() {
        val m = NameCardConsentMachine()
        m.onEvent(Event.LocalReceiveOnly)
        m.onEvent(Event.PeerReceiveOnly) // resolves + closes
        assertTrue(m.isClosed)
        assertEquals(emptyList<Effect>(), m.onEvent(Event.LocalShare))
        assertEquals(emptyList<Effect>(), m.onEvent(Event.PeerCardArrived))
        assertEquals(emptyList<Effect>(), m.onEvent(Event.Timeout))
        assertEquals(State.DONE_BOTH_DECLINED, m.state)
    }

    @Test
    fun `duplicate local choice is ignored`() {
        val m = NameCardConsentMachine()
        assertEquals(listOf(share(true), Effect.TransmitCard, Effect.ShowHeadsUpWaiting), m.onEvent(Event.LocalShare))
        assertEquals(emptyList<Effect>(), m.onEvent(Event.LocalShare))
        assertEquals(emptyList<Effect>(), m.onEvent(Event.LocalReceiveOnly))
    }

    @Test
    fun `duplicate peer choice and duplicate card are ignored`() {
        val m = NameCardConsentMachine()
        assertEquals(emptyList<Effect>(), m.onEvent(Event.PeerShare))
        assertEquals(listOf(Effect.SaveCardAndRipple), m.onEvent(Event.PeerCardArrived))
        assertEquals(emptyList<Effect>(), m.onEvent(Event.PeerCardArrived))
        assertEquals(emptyList<Effect>(), m.onEvent(Event.PeerShare))
    }

    @Test
    fun `TransmitCard only ever follows a Share, never a Receive Only`() {
        // Guard against a regression that sends the card on decline (plan D1/D2).
        for (events in listOf(
            arrayOf(Event.LocalReceiveOnly, Event.PeerShare, Event.PeerCardArrived),
            arrayOf(Event.LocalReceiveOnly, Event.PeerReceiveOnly),
            arrayOf(Event.PeerReceiveOnly, Event.LocalReceiveOnly),
        )) {
            val (fx, _) = run(*events)
            assertTrue("no TransmitCard for a Receive-Only local choice: $events", Effect.TransmitCard !in fx)
        }
    }
}
