package de.test.antennapod.playback;

import java.util.List;

final class ResolvedContentOracle {
    static final int A = 1;
    static final int B = 2;
    static final int C = 4;
    static final int ALL = A | B | C;
    private static final double AD_A = 2 + 2299 * 1152.0 / 44100;
    private static final double TOLERANCE = 0.25;

    private ResolvedContentOracle() {
    }

    static int matchingProgramEditions(List<double[]> consumedWindows) {
        int candidates = consumedWindows.isEmpty() ? 0 : ALL;
        for (double[] window : consumedWindows) {
            double media = window[0];
            double program = (window[1] - 20) / (9980.0 / 120);
            int matches = 0;
            if (media >= 5 + AD_A && media < 120 + AD_A
                    && Math.abs(program - (media - AD_A)) < TOLERANCE) {
                matches |= A;
            }
            if (media >= 7 && media < 122 && Math.abs(program - (media - 2)) < TOLERANCE) {
                matches |= B | C;
            }
            candidates &= matches;
        }
        return candidates;
    }

    static int editionsAfterOneTransitionAndReplay(int initial, int[] firstPass, int[] replay) {
        if (firstPass.length == 0 || firstPass.length != replay.length) {
            throw new IllegalArgumentException("Require one complete replay of the target list");
        }
        int unchanged = initial & ALL;
        int changed = 0;
        for (int candidates : firstPass) {
            changed = (changed | (unchanged == 0 ? 0 : ALL)) & candidates;
            unchanged &= candidates;
        }
        int finalEditions = unchanged | changed;
        for (int candidates : replay) {
            finalEditions &= candidates;
        }
        return finalEditions;
    }
}
