package de.danoeh.antennapod.ui.chapters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import de.danoeh.antennapod.model.feed.Chapter;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import de.danoeh.antennapod.parser.media.MediaFormatDetector;

@RunWith(RobolectricTestRunner.class)
public class ChapterUtilsTest {
    private final List<String> ranges = new ArrayList<>();

    @Test
    public void untaggedMpegNeedsOnlyABoundedHeaderRequest() throws Exception {
        byte[] bytes = new byte[1000000];
        bytes[0] = (byte) 0xff;
        bytes[1] = (byte) 0xfb;
        bytes[2] = (byte) 0x90;
        assertTrue(loadNetworkChapters(bytes, 1).isEmpty());
        assertEquals(List.of("bytes=0-63"), ranges);
    }

    @Test
    public void id3ChaptersNeedOnlyTheDeclaredTag() throws Exception {
        byte[] bytes = Files.readAllBytes(new File(getClass().getClassLoader()
                .getResource("auphonic.mp3").toURI()).toPath());
        int size = (bytes[6] << 21) | (bytes[7] << 14) | (bytes[8] << 7) | bytes[9];
        List<Chapter> chapters = loadNetworkChapters(bytes, 2);
        assertEquals(4, chapters.size());
        assertEquals("Chapter 1 - ❤️😊", chapters.get(0).getTitle());
        assertEquals(List.of("bytes=0-63", "bytes=0-" + (size + 9)), ranges);
        assertTrue(size + 10 < bytes.length);
    }

    private List<Chapter> loadNetworkChapters(byte[] bytes, int requests) throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        AntennapodHttpClient.setCacheDirectory(new File(context.getCacheDir(), "chapter-probe-test"));
        AntennapodHttpClient.reinit();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ServerSocket server = new ServerSocket(0);
        try {
            server.setSoTimeout(5000);
            Thread response = new Thread(() -> {
                try {
                    for (int request = 0; request < requests; request++) {
                        try (Socket socket = server.accept()) {
                            socket.setSoTimeout(5000);
                            BufferedReader input = new BufferedReader(new InputStreamReader(
                                    socket.getInputStream(), StandardCharsets.US_ASCII));
                            String range = null;
                            for (String line; !(line = input.readLine()).isEmpty();) {
                                if (line.startsWith("Range: ")) {
                                    range = line.substring(7);
                                }
                            }
                            ranges.add(range);
                            int length = range == null ? bytes.length : Math.min(bytes.length,
                                    Integer.parseInt(range.substring(range.indexOf('-') + 1)) + 1);
                            socket.getOutputStream().write(((range == null ? "HTTP/1.1 200 OK" : "HTTP/1.1 206 Partial")
                                    + "\r\nContent-Length: " + length + "\r\nContent-Range: bytes 0-" + (length - 1)
                                    + "/" + bytes.length + "\r\nConnection: close\r\n\r\n")
                                    .getBytes(StandardCharsets.US_ASCII));
                            socket.getOutputStream().write(bytes, 0, length);
                        }
                    }
                    server.close();
                } catch (Throwable error) {
                    failure.set(error);
                }
            }, "chapter-probe-test");
            response.start();
            FeedMedia media = new FeedMedia(1, null, 0, 0, 0, "audio/mpeg", null,
                    "http://127.0.0.1:" + server.getLocalPort() + "/episode", 1, null, 0, 0);
            List<Chapter> chapters = ChapterUtils.loadChaptersFromMediaFile(media, context);
            response.join(5000);
            assertFalse(response.isAlive());
            assertNull(failure.get());
            return chapters;
        } finally {
            server.close();
        }
    }

    @Test
    public void testMimeMpeg_selectsId3() {
        assertEquals(MediaFormatDetector.Format.ID3,
                ChapterUtils.detectHintFromMetadata("audio/mpeg", "http://example.com/file?token=abc"));
    }

    @Test
    public void testMimeMp3_selectsId3() {
        assertEquals(MediaFormatDetector.Format.ID3,
                ChapterUtils.detectHintFromMetadata("audio/mp3", "http://example.com/file?token=abc"));
    }

    @Test
    public void testMimeXMp3_selectsId3() {
        assertEquals(MediaFormatDetector.Format.ID3,
                ChapterUtils.detectHintFromMetadata("audio/x-mp3", "http://example.com/file?token=abc"));
    }

    @Test
    public void testMimeOgg_selectsOgg() {
        assertEquals(MediaFormatDetector.Format.OGG,
                ChapterUtils.detectHintFromMetadata("audio/ogg", "http://example.com/file?token=abc"));
    }

    @Test
    public void testMimeApplicationOgg_selectsOgg() {
        assertEquals(MediaFormatDetector.Format.OGG,
                ChapterUtils.detectHintFromMetadata("application/ogg", "http://example.com/file?token=abc"));
    }

    @Test
    public void testMimeOpus_selectsOgg() {
        assertEquals(MediaFormatDetector.Format.OGG,
                ChapterUtils.detectHintFromMetadata("audio/opus", "http://example.com/file?token=abc"));
    }

    @Test
    public void testMimeApplicationOpus_selectsOgg() {
        assertEquals(MediaFormatDetector.Format.OGG,
                ChapterUtils.detectHintFromMetadata("application/opus", "http://example.com/file?token=abc"));
    }

    @Test
    public void testMimeFlac_selectsFlac() {
        assertEquals(MediaFormatDetector.Format.FLAC,
                ChapterUtils.detectHintFromMetadata("audio/flac", "http://example.com/file?token=abc"));
    }

    @Test
    public void testExtensionFlac_selectsFlac() {
        assertEquals(MediaFormatDetector.Format.FLAC,
                ChapterUtils.detectHintFromMetadata(null, "http://example.com/file.flac"));
    }

    @Test
    public void testMimeM4a_selectsM4a() {
        assertEquals(MediaFormatDetector.Format.M4A,
                ChapterUtils.detectHintFromMetadata("audio/mp4", "http://example.com/file?token=abc"));
    }

    @Test
    public void testMimeVideoMp4_selectsM4a() {
        assertEquals(MediaFormatDetector.Format.M4A,
                ChapterUtils.detectHintFromMetadata("video/mp4", "http://example.com/file?token=abc"));
    }

    @Test
    public void testMimeM4b_selectsM4a() {
        assertEquals(MediaFormatDetector.Format.M4A,
                ChapterUtils.detectHintFromMetadata("audio/m4b", "http://example.com/file?token=abc"));
    }

    @Test
    public void testMimeXM4b_selectsM4a() {
        assertEquals(MediaFormatDetector.Format.M4A,
                ChapterUtils.detectHintFromMetadata("audio/x-m4b", "http://example.com/file?token=abc"));
    }

    @Test
    public void testMimePreferredOverExtension() {
        assertEquals(MediaFormatDetector.Format.ID3,
                ChapterUtils.detectHintFromMetadata("audio/mpeg", "http://example.com/file.m4a?token=abc"));
    }

    @Test
    public void testExtensionMp3_selectsId3() {
        assertEquals(MediaFormatDetector.Format.ID3,
                ChapterUtils.detectHintFromMetadata(null, "http://example.com/file.mp3?token=abc"));
    }

    @Test
    public void testExtensionOgg_selectsOgg() {
        assertEquals(MediaFormatDetector.Format.OGG,
                ChapterUtils.detectHintFromMetadata(null, "http://example.com/file.ogg?token=abc"));
    }

    @Test
    public void testExtensionOpus_selectsOgg() {
        assertEquals(MediaFormatDetector.Format.OGG,
                ChapterUtils.detectHintFromMetadata(null, "http://example.com/file.opus?token=abc"));
    }

    @Test
    public void testExtensionM4a_selectsM4a() {
        assertEquals(MediaFormatDetector.Format.M4A,
                ChapterUtils.detectHintFromMetadata(null, "http://example.com/file.m4a?token=abc"));
    }

    @Test
    public void testExtensionMp4_selectsM4a() {
        assertEquals(MediaFormatDetector.Format.M4A,
                ChapterUtils.detectHintFromMetadata(null, "http://example.com/file.mp4?token=abc"));
    }

    @Test
    public void testExtensionM4b_selectsM4a() {
        assertEquals(MediaFormatDetector.Format.M4A,
                ChapterUtils.detectHintFromMetadata(null, "http://example.com/file.m4b?token=abc"));
    }

    @Test
    public void testUnknownMetadata_detectsUnknown() {
        assertEquals(MediaFormatDetector.Format.UNKNOWN,
                ChapterUtils.detectHintFromMetadata(null, "http://example.com/file.unknown?token=abc"));
    }

    @Test
    public void testFallbackOrderOggHint_skipsOgg() {
        assertEquals(MediaFormatDetector.Format.ID3,
                ChapterUtils.getFallbackOrder(MediaFormatDetector.Format.OGG)[0]);
        assertEquals(MediaFormatDetector.Format.M4A,
                ChapterUtils.getFallbackOrder(MediaFormatDetector.Format.OGG)[1]);
    }

    @Test
    public void testFallbackOrderM4aHint_skipsM4a() {
        assertEquals(MediaFormatDetector.Format.ID3,
                ChapterUtils.getFallbackOrder(MediaFormatDetector.Format.M4A)[0]);
        assertEquals(MediaFormatDetector.Format.OGG,
                ChapterUtils.getFallbackOrder(MediaFormatDetector.Format.M4A)[1]);
    }

    @Test
    public void testFallbackOrderUnknownHint_skipsId3BecauseAlreadyTried() {
        assertEquals(MediaFormatDetector.Format.OGG,
                ChapterUtils.getFallbackOrder(MediaFormatDetector.Format.UNKNOWN)[0]);
        assertEquals(MediaFormatDetector.Format.M4A,
                ChapterUtils.getFallbackOrder(MediaFormatDetector.Format.UNKNOWN)[1]);
    }

    @Test
    public void testLoadChaptersFromMediaFile_stitchedMp3_readsChapters() throws Exception {
        List<Chapter> chapters = loadFixtureChapters("auphonic.mp3", "audio/mpeg");

        assertEquals(4, chapters.size());
        assertEquals(0, chapters.get(0).getStart());
        assertEquals("Chapter 1 - ❤️😊", chapters.get(0).getTitle());
    }

    @Test
    public void testLoadChaptersFromMediaFile_magicBytesOverrideMetadataHint() throws Exception {
        List<Chapter> chapters = loadFixtureChapters("auphonic.mp3", "audio/ogg");

        assertEquals(4, chapters.size());
        assertEquals(0, chapters.get(0).getStart());
        assertEquals("Chapter 1 - ❤️😊", chapters.get(0).getTitle());
    }

    @Test
    public void testLoadChaptersFromMediaFile_stitchedOgg_readsChapters() throws Exception {
        List<Chapter> chapters = loadFixtureChapters("auphonic.ogg", "audio/ogg");

        assertEquals(4, chapters.size());
        assertEquals(0, chapters.get(0).getStart());
        assertEquals("Chapter 1 - ❤️😊", chapters.get(0).getTitle());
    }

    @Test
    public void testLoadChaptersFromMediaFile_stitchedM4a_readsChapters() throws Exception {
        List<Chapter> chapters = loadFixtureChapters("nero-chapters.m4a", "audio/mp4");

        assertEquals(4, chapters.size());
        assertEquals(0, chapters.get(0).getStart());
        assertEquals("Chapter 1 - ❤️😊", chapters.get(0).getTitle());
    }

    private List<Chapter> loadFixtureChapters(String filename, String mimeType) throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String path = new File(getClass().getClassLoader().getResource(filename).toURI()).getPath();

        FeedMedia media = new FeedMedia(1, null, 0, 0, 0, mimeType,
                path, "http://example.com/" + filename, 1, null, 0, 0);
        return ChapterUtils.loadChaptersFromMediaFile(media, context);
    }
}
