package de.test.antennapod.playback;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static de.test.antennapod.playback.ResolvedContentOracle.A;
import static de.test.antennapod.playback.ResolvedContentOracle.B;
import static de.test.antennapod.playback.ResolvedContentOracle.C;
import static de.test.antennapod.playback.ResolvedContentOracle.editionsAfterOneTransitionAndReplay;
import static de.test.antennapod.playback.ResolvedContentOracle.matchingProgramEditions;
import static org.junit.Assert.assertEquals;

public class ResolvedContentOracleTest {
    @Test
    public void coherentSegmentsKeepAllCompatibleEditions() {
        assertEquals(A, matchingProgramEditions(Arrays.asList(
                window(90, 27.944489795918365), window(91, 28.944489795918365))));
        assertEquals(B | C, matchingProgramEditions(Arrays.asList(window(90, 88), window(91, 89))));
    }

    @Test
    public void matchingEachWindowToADifferentEditionDoesNotMakeACoherentSegment() {
        assertEquals(0, matchingProgramEditions(Arrays.asList(
                window(90, 27.944489795918365), window(91, 89))));
    }

    @Test
    public void missingOrInvalidEvidenceIsNotCoherentPlayback() {
        assertEquals(0, matchingProgramEditions(Collections.emptyList()));
        assertEquals(0, matchingProgramEditions(Collections.singletonList(window(90, Double.NaN))));
        assertEquals(0, matchingProgramEditions(Collections.singletonList(window(123, 121))));
    }

    @Test
    public void cleanOneTimeTransitionAndStableReplayAreAccepted() {
        assertEquals(B | C, editionsAfterOneTransitionAndReplay(A,
                new int[] {A, B | C, B | C}, new int[] {B | C, B | C, B | C}));
        assertEquals(A, editionsAfterOneTransitionAndReplay(B | C,
                new int[] {A, A, A}, new int[] {A, A, A}));
    }

    @Test
    public void stableAmbiguousEditionDoesNotRequireInventingItsIdentity() {
        assertEquals(B | C, editionsAfterOneTransitionAndReplay(B | C,
                new int[] {B | C, B | C, B | C}, new int[] {B | C, B | C, B | C}));
    }

    @Test
    public void alternatingRegionsAreRejected() {
        assertEquals(0, editionsAfterOneTransitionAndReplay(B | C,
                new int[] {A, B | C, A}, new int[] {A, B | C, A}));
    }

    @Test
    public void replayMustMatchOneFinalEdition() {
        assertEquals(0, editionsAfterOneTransitionAndReplay(A,
                new int[] {A, B | C, B | C}, new int[] {B | C, A, B | C}));
    }

    @Test
    public void laterCleanAudioCannotEraseAnEarlierCorruptSegment() {
        assertEquals(0, editionsAfterOneTransitionAndReplay(A,
                new int[] {0, B | C, B | C}, new int[] {B | C, B | C, B | C}));
    }

    @Test(expected = IllegalArgumentException.class)
    public void partialReplayIsNotAccepted() {
        editionsAfterOneTransitionAndReplay(A, new int[] {A, A, A}, new int[] {A});
    }

    private double[] window(double media, double program) {
        return new double[] {media, 20 + program * (9980.0 / 120)};
    }
}
