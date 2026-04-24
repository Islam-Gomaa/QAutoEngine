package utilities;

import io.qameta.allure.Allure;
import org.monte.media.Format;
import org.monte.media.Registry;
import org.monte.media.math.Rational;
import org.monte.screenrecorder.ScreenRecorder;

import java.awt.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

import static org.monte.media.FormatKeys.*;
import static org.monte.media.VideoFormatKeys.*;

public class ScreenRecorderUtil extends ScreenRecorder {

    private String name;
    private File recordedFile;

    public ScreenRecorderUtil(GraphicsConfiguration cfg, Rectangle captureArea,
                              Format fileFormat, Format screenFormat,
                              Format mouseFormat, Format audioFormat,
                              File movieFolder, String name)
            throws IOException, AWTException {

        super(cfg, captureArea, fileFormat, screenFormat, mouseFormat, audioFormat, movieFolder);
        this.name = name;
    }

    @Override
    protected File createMovieFile(Format fileFormat) throws IOException {

        if (!movieFolder.exists()) {
            movieFolder.mkdirs();
        }

        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());

        recordedFile = new File(movieFolder,
                name + "_" + timestamp + "." + Registry.getInstance().getExtension(fileFormat));

        return recordedFile;
    }

    public File getRecordedFile() {
        return recordedFile;
    }

    // 🔥 create instance per test (مش static)
    public static ScreenRecorderUtil startRecord(String name) throws Exception {

        File folder = new File("reports/videos");

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        Rectangle captureSize = new Rectangle(0, 0, screenSize.width, screenSize.height);

        GraphicsConfiguration gc = GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .getDefaultScreenDevice()
                .getDefaultConfiguration();

        ScreenRecorderUtil recorder = new ScreenRecorderUtil(
                gc,
                captureSize,
                new Format(MediaTypeKey, MediaType.FILE, MimeTypeKey, MIME_AVI),
                new Format(MediaTypeKey, MediaType.VIDEO,
                        EncodingKey, ENCODING_AVI_TECHSMITH_SCREEN_CAPTURE,
                        CompressorNameKey, ENCODING_AVI_TECHSMITH_SCREEN_CAPTURE,
                        DepthKey, 24,
                        FrameRateKey, Rational.valueOf(15),
                        QualityKey, 1.0f,
                        KeyFrameIntervalKey, 15 * 60),
                new Format(MediaTypeKey, MediaType.VIDEO,
                        EncodingKey, "black",
                        FrameRateKey, Rational.valueOf(30)),
                null,
                folder,
                name
        );

        recorder.start();
        return recorder;
    }

    public void stopAndAttachToAllure() throws Exception {
        this.stop();

        if (recordedFile != null && recordedFile.exists()) {

            Allure.addAttachment(
                    "Execution Video",
                    "video/avi",
                    new FileInputStream(recordedFile),
                    ".avi"
            );
        }
    }
}