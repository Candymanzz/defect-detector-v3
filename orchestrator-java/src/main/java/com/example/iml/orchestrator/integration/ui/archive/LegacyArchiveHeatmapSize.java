package com.example.iml.orchestrator.integration.ui.archive;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Compatibility for archives written before heatmap dimensions were persisted reliably.
 * gray_u8 contains exactly one byte per pixel; choose the factor pair closest to the JPEG aspect ratio.
 */
final class LegacyArchiveHeatmapSize {

    private static final Logger LOG = LogManager.getLogger(LegacyArchiveHeatmapSize.class);

    private LegacyArchiveHeatmapSize() {
    }

    static int[] infer(Path frameJpeg, Path heatmapU8) {
        try {
            long pixelCount = Files.size(heatmapU8);
            if (pixelCount <= 0 || pixelCount > (long) Integer.MAX_VALUE * Integer.MAX_VALUE) {
                return new int[]{0, 0};
            }
            BufferedImage frame = ImageIO.read(frameJpeg.toFile());
            if (frame == null || frame.getWidth() <= 0 || frame.getHeight() <= 0) {
                return new int[]{0, 0};
            }
            if (pixelCount == (long) frame.getWidth() * frame.getHeight()) {
                return new int[]{frame.getWidth(), frame.getHeight()};
            }

            double targetAspect = (double) frame.getWidth() / frame.getHeight();
            int bestWidth = 0;
            int bestHeight = 0;
            double bestScore = Double.POSITIVE_INFINITY;
            for (long divisor = 1; divisor * divisor <= pixelCount; divisor++) {
                if (pixelCount % divisor != 0) {
                    continue;
                }
                long quotient = pixelCount / divisor;
                if (quotient > Integer.MAX_VALUE) {
                    continue;
                }
                int[][] candidates = {
                        {(int) quotient, (int) divisor},
                        {(int) divisor, (int) quotient}
                };
                for (int[] candidate : candidates) {
                    double aspect = (double) candidate[0] / candidate[1];
                    double score = Math.abs(Math.log(aspect / targetAspect));
                    if (score < bestScore) {
                        bestScore = score;
                        bestWidth = candidate[0];
                        bestHeight = candidate[1];
                    }
                }
            }
            if (bestWidth > 0) {
                LOG.info(
                        "inferred legacy archive heatmap size {}x{} from {} bytes frame={}",
                        bestWidth,
                        bestHeight,
                        pixelCount,
                        frameJpeg
                );
            }
            return new int[]{bestWidth, bestHeight};
        } catch (Exception e) {
            LOG.debug("legacy archive heatmap size inference failed {}: {}", heatmapU8, e.getMessage());
            return new int[]{0, 0};
        }
    }
}
