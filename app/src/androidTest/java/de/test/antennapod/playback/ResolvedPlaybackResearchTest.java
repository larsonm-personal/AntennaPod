package de.test.antennapod.playback;

import de.test.antennapod.IgnoreOnCi;

import org.junit.Test;

@IgnoreOnCi
public class ResolvedPlaybackResearchTest {
    @Test
    public void previouslySelectedEditionMatchesLaterSeeks() throws Exception {
        ResolvedUrlPlaybackTest experiment = new ResolvedUrlPlaybackTest();
        try {
            experiment.setUp();
            experiment.changedVariantRemainsAccessibleAcrossBackwardAndForwardSeeks();
        } finally {
            experiment.tearDown();
        }
    }
}
