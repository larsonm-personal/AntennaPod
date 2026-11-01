package de.test.antennapod.playback.hazards.support;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.test.filters.LargeTest;

import org.junit.Test;

import de.test.antennapod.IgnoreOnCi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// Checks that the passage matcher accepts replacement samples and rejects missing, silent or wrong content.
@LargeTest
@OptIn(markerClass = UnstableApi.class)
@IgnoreOnCi
public class PassageOracleTest extends PlaybackCase {
    @Test
    public void continuousReplayRequiresCompletePassageButAllowsRecovery() {
        List<ResolvedTaggedAudio.Heard> passage = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            double band = index < 4 ? 500 : 3500;
            passage.add(new ResolvedTaggedAudio.Heard(new double[] {index * 0.25 + 0.125,
                    band + (75.125 + index * 0.25) * 12.5}, index < 4 ? 0 : 1));
        }
        assertTrue(containsPassage(passage, 75));
        List<ResolvedTaggedAudio.Heard> interrupted = new ArrayList<>(passage);
        interrupted.remove(3);
        assertFalse(containsPassage(interrupted, 75));
        interrupted = new ArrayList<>(passage);
        interrupted.set(3, new ResolvedTaggedAudio.Heard(new double[] {0.875, Double.NaN}, 0));
        assertFalse(containsPassage(interrupted, 75));
        interrupted.addAll(passage);
        assertTrue(containsPassage(interrupted, 75));
        assertFalse(containsPassage(passage.subList(0, 7), 75));
        assertFalse(containsPassage(passage, 35));
    }

    @Test
    public void passageRecognitionAllowsReplacementButRejectsMissingContent() {
        List<ResolvedTaggedAudio.Heard> windows = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            double band = index % 2 == 0 ? 500 : 3500;
            windows.add(new ResolvedTaggedAudio.Heard(new double[] {0.125,
                    band + (75 + index * 0.25) * 12.5}, index));
        }
        assertTrue(matchesPassage(windows, 75));
        windows.set(2, new ResolvedTaggedAudio.Heard(new double[] {0.625, Double.NaN}, 2));
        assertFalse(matchesPassage(windows, 75));
        windows.set(2, new ResolvedTaggedAudio.Heard(new double[] {0.125, 500 + 15 * 12.5}, 2));
        assertFalse(matchesPassage(windows, 75));
        assertFalse(matchesPassage(Collections.emptyList(), 75));
    }
}
