package de.test.antennapod.ui;

import android.content.Intent;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.net.common.PlaybackTrace;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.screen.preferences.PreferenceActivity;
import org.apache.commons.io.IOUtils;
import org.awaitility.Awaitility;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static de.test.antennapod.EspressoTestUtils.clickPreference;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiagnosticExportTest {
    @Rule
    public ActivityTestRule<PreferenceActivity> activity =
            new ActivityTestRule<>(PreferenceActivity.class, false, false);

    @Test
    public void exportWaitsOffMainAndSurvivesLeavingReportScreen() throws Exception {
        activity.launchActivity(new Intent());
        clickPreference(R.string.report_bug_title);
        String marker = "BACKGROUND_EXPORT_" + System.nanoTime();
        PlaybackTrace.detail("exportFixture", marker);
        Field field = PlaybackTrace.class.getDeclaredField("writer");
        field.setAccessible(true);
        ThreadPoolExecutor writer = (ThreadPoolExecutor) field.get(null);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean expired = new AtomicBoolean();
        writer.execute(() -> {
            entered.countDown();
            try {
                expired.set(!release.await(60, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            openActionBarOverflowOrOptionsMenu(InstrumentationRegistry.getInstrumentation().getTargetContext());
            onView(withText(R.string.export_logs_menu_title)).perform(click());
            onView(withText(R.string.confirm_label)).perform(click());
            pressBack();
            assertFalse("Export blocked the main thread until the writer resumed", expired.get());
        } finally {
            release.countDown();
        }
        File exported = new File(UserPreferences.getDataFolder(null), "full-logs.txt");
        Awaitility.await().atMost(30, TimeUnit.SECONDS).until(() -> {
            if (!exported.exists()) {
                return false;
            }
            try (FileInputStream input = new FileInputStream(exported)) {
                String text = IOUtils.toString(input, StandardCharsets.UTF_8);
                int retained = text.lastIndexOf("Retained playback diagnostics");
                return retained >= 0 && text.substring(retained).contains(marker);
            }
        });
    }
}
