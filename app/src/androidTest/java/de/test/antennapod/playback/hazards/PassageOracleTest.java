package de.test.antennapod.playback.hazards;

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

@LargeTest
@OptIn(markerClass = UnstableApi.class)
@IgnoreOnCi
public class PassageOracleTest extends PlaybackCase {
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
