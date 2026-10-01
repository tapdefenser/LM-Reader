package com.lmreader.ui.reader.translation

import org.junit.Test
import org.junit.Assert.*

class DraftNavigationGateTest {
    @Test fun cleanPageNavigatesWithoutPrompt() {
        val gate=DraftNavigationGate();var moves=0
        assertTrue(gate.request(false,false) {moves++});assertEquals(1,moves);assertFalse(gate.waiting)
    }
    @Test fun repeatedSwipeCannotReplacePendingDestinationOrSaveTwice() {
        val gate=DraftNavigationGate();val moves=mutableListOf<String>()
        assertFalse(gate.request(true,false) {moves+="first"})
        assertFalse(gate.request(true,false) {moves+="second"})
        assertTrue(moves.isEmpty());gate.resume();gate.resume()
        assertEquals(listOf("first"),moves)
    }
    @Test fun failedSaveRetainsPendingActionUntilRetrySucceeds() {
        val gate=DraftNavigationGate();var moved=false
        gate.request(true,false) {moved=true}
        assertFalse(gate.request(false,true) {moved=true})
        assertTrue(gate.waiting);assertFalse(moved)
        gate.resume();assertTrue(moved);assertFalse(gate.waiting)
    }
    @Test fun cancellingPromptRetainsPageAndAllowsANewDestination() {
        val gate=DraftNavigationGate();var destination=0
        gate.request(true,false) {destination=1};gate.cancel();gate.resume()
        assertEquals(0,destination)
        gate.request(true,false) {destination=2};gate.resume();assertEquals(2,destination)
    }
}
