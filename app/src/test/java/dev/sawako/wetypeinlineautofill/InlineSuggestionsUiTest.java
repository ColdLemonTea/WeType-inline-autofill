package dev.sawako.wetypeinlineautofill;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;

public class InlineSuggestionsUiTest {
    @Test
    public void keepsFirstPinnedSeparateAndOrdersTheRest() {
        assertArrayEquals(new int[]{1, 3, 0, 2},
                InlineSuggestionsUi.orderSuggestions(
                        new boolean[]{false, true, false, true}));
        assertArrayEquals(new int[]{-1, 0, 1},
                InlineSuggestionsUi.orderSuggestions(new boolean[]{false, false}));
        assertArrayEquals(new int[]{0},
                InlineSuggestionsUi.orderSuggestions(new boolean[]{true}));
    }

    @Test
    public void onlyClearsSuggestionsForANewInputTarget() {
        assertTrue(InlineSuggestionsUi.shouldClearOnStartInputView(false));
        assertFalse(InlineSuggestionsUi.shouldClearOnStartInputView(true));
    }

    @Test
    public void waitsForAtLeastOneRenderedSuggestionBeforeShowingShell() {
        assertFalse(InlineSuggestionsUi.hasReadyContent(false, false));
        assertTrue(InlineSuggestionsUi.hasReadyContent(true, false));
        assertTrue(InlineSuggestionsUi.hasReadyContent(false, true));
    }

    @Test
    public void followsCandidateListReplacementWithoutDroppingIncrementalState() {
        assertTrue(InlineSuggestionsUi.candidateActiveAfterUpdate(false, true, true));
        assertTrue(InlineSuggestionsUi.candidateActiveAfterUpdate(true, false, false));
        assertFalse(InlineSuggestionsUi.candidateActiveAfterUpdate(true, true, false));
    }

    @Test
    public void resolvesObfuscatedCandidateUpdateBySignature() throws Exception {
        assertEquals("renamed", InlineSuggestionsUi.findCandidateUpdateMethod(
                CandidateUpdateFixture.class).getName());
    }

    private static final class CandidateUpdateFixture {
        public void renamed(ArrayList<Object> candidates, int type, boolean newList,
                            boolean hasMore) {
        }

        public void unrelated(ArrayList<Object> candidates, int type) {
        }
    }
}
