package com.example.iml.positioning.analysis;

import com.example.iml.positioning.dto.NormPoint;
import com.example.iml.positioning.dto.PositioningRequest;
import com.example.iml.positioning.dto.PositioningResponse;
import com.example.iml.positioning.dto.RoiRect;
import com.example.iml.positioning.shm.ShmMatWriter;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.DMatch;
import org.opencv.core.KeyPoint;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.core.TermCriteria;
import org.opencv.features2d.BFMatcher;
import org.opencv.features2d.ORB;
import org.opencv.imgproc.Imgproc;
import org.opencv.video.Video;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Full-frame ORB + RANSAC, then ECC refine in the reference interest region.
 * Large pose discrepancy is expected; PASS = aligned frame written.
 */
public final class BucketPositioningService {

    private static final int MAX_ALIGNMENT_DIM = 1024;
    private static final int ORB_FEATURES = 5000;
    private static final double RANSAC_REPROJ_THRESHOLD = 8.0;
    private static final int MIN_MATCHES = 8;
    private static final double FALLBACK_FAIL_MM = 9999.0;
    private static final double FALLBACK_FAIL_ROTATION_DEG = 9999.0;

    private final ShmMatWriter shmWriter;
    private final ORB orb;
    private final BFMatcher matcher;
    private PreparedReference preparedReferenceCache;

    public BucketPositioningService(ShmMatWriter shmWriter) {
        this.shmWriter = shmWriter;
        this.orb = ORB.create(ORB_FEATURES);
        this.matcher = BFMatcher.create(Core.NORM_HAMMING, false);
    }

    public PositioningResponse position(
            Mat reference,
            Mat current,
            PositioningRequest request,
            String referenceCacheKey
    ) {
        long tTotal0 = System.nanoTime();
        double stageMsOrb = 0;
        double stageMsWarp = 0;
        double stageMsEcc = 0;
        double stageMsWrite = 0;
        Mat aligned = null;
        Mat homography = null;
        try {
            validateInputFrames(reference, current);

            long tOrb0 = System.nanoTime();
            AlignmentResult alignment = alignFullFrame(reference, current, request.pixelsToMm(), referenceCacheKey);
            stageMsOrb = nanosToMs(System.nanoTime() - tOrb0);
            homography = alignment.homographyRefToCurrent;
            boolean matched = homography != null && !homography.empty();

            boolean withinSoftTolerance = matched
                    && Math.abs(alignment.shiftXmm) <= request.maxShiftMm()
                    && Math.abs(alignment.shiftYmm) <= request.maxShiftMm()
                    && Math.abs(alignment.rotationDeg) <= request.maxRotationDeg();

            boolean alignedWritten = false;
            String outputName = request.outputShmName() == null ? "" : request.outputShmName().trim();
            if (request.writeAligned() && !outputName.isEmpty() && matched) {
                long tWarp0 = System.nanoTime();
                aligned = warpCurrentToReference(current, homography);
                stageMsWarp = nanosToMs(System.nanoTime() - tWarp0);

                Rect refineRect = resolveMainRect(request, current.cols(), current.rows());
                long tEcc0 = System.nanoTime();
                Mat refined = refineWithEcc(aligned, reference, refineRect, request.mainRoiPolygonNorm());
                stageMsEcc = nanosToMs(System.nanoTime() - tEcc0);
                if (refined != aligned) {
                    aligned.release();
                    aligned = refined;
                }

                long tWrite0 = System.nanoTime();
                shmWriter.writeBgrMat(outputName, aligned);
                stageMsWrite = nanosToMs(System.nanoTime() - tWrite0);
                alignedWritten = true;
            }

            boolean overallPass = request.writeAligned() ? alignedWritten : matched;
            if (request.writeAligned() && outputName.isEmpty()) {
                overallPass = false;
            }

            double stageMsTotal = nanosToMs(System.nanoTime() - tTotal0);
            return new PositioningResponse(
                    alignment.shiftXmm,
                    alignment.shiftYmm,
                    alignment.rotationDeg,
                    homographyToArray(homography),
                    withinSoftTolerance,
                    overallPass,
                    alignedWritten,
                    alignedWritten ? outputName : "",
                    current.cols(),
                    current.rows(),
                    current.cols() * 3,
                    overallPass ? "PASS" : "FAIL",
                    stageMsOrb,
                    stageMsWarp,
                    stageMsEcc,
                    stageMsWrite,
                    stageMsTotal
            );
        } finally {
            release(aligned);
            if (homography != null) {
                homography.release();
            }
        }
    }

    private AlignmentResult alignFullFrame(Mat reference, Mat current, double pixelsToMm, String referenceCacheKey) {
        Mat curGray = new Mat();
        Mat curGrayScaled = null;
        Mat curDescriptors = new Mat();
        MatOfKeyPoint curKeypoints = new MatOfKeyPoint();
        try {
            PreparedReference preparedReference = getOrBuildPreparedReference(reference, referenceCacheKey);
            Imgproc.cvtColor(current, curGray, Imgproc.COLOR_BGR2GRAY);
            ResizeResult curResize = resizeForProcessing(curGray, MAX_ALIGNMENT_DIM);
            curGrayScaled = curResize.mat;

            orb.detectAndCompute(curGrayScaled, new Mat(), curKeypoints, curDescriptors);
            if (preparedReference.descriptors.empty() || curDescriptors.empty()) {
                return failedAlignment();
            }

            List<DMatch> goodMatches = ratioTestMatches(preparedReference.descriptors, curDescriptors, 0.80f);
            if (goodMatches.size() < MIN_MATCHES) {
                goodMatches = ratioTestMatches(preparedReference.descriptors, curDescriptors, 0.92f);
            }
            if (goodMatches.size() < MIN_MATCHES) {
                return failedAlignment();
            }

            KeyPoint[] refPoints = preparedReference.keypoints;
            KeyPoint[] curPoints = curKeypoints.toArray();
            List<Point> srcPoints = new ArrayList<>(goodMatches.size());
            List<Point> dstPoints = new ArrayList<>(goodMatches.size());
            for (DMatch match : goodMatches) {
                if (match.queryIdx < 0 || match.queryIdx >= refPoints.length
                        || match.trainIdx < 0 || match.trainIdx >= curPoints.length) {
                    continue;
                }
                srcPoints.add(refPoints[match.queryIdx].pt);
                dstPoints.add(curPoints[match.trainIdx].pt);
            }
            if (srcPoints.size() < MIN_MATCHES) {
                return failedAlignment();
            }

            MatOfPoint2f src = new MatOfPoint2f();
            MatOfPoint2f dst = new MatOfPoint2f();
            Mat inliersMask = new Mat();
            Mat scaledHomography = new Mat();
            try {
                src.fromList(srcPoints);
                dst.fromList(dstPoints);
                scaledHomography = Calib3d.findHomography(
                        src, dst, Calib3d.RANSAC, RANSAC_REPROJ_THRESHOLD, inliersMask);
                if (scaledHomography.empty()) {
                    return failedAlignment();
                }
                if (Core.countNonZero(inliersMask) < MIN_MATCHES) {
                    return failedAlignment();
                }
                Mat full = toOriginalScaleHomography(
                        scaledHomography, preparedReference.scaleX, preparedReference.scaleY);
                double shiftXPx = full.get(0, 2)[0];
                double shiftYPx = full.get(1, 2)[0];
                double rotationDeg = Math.toDegrees(Math.atan2(full.get(1, 0)[0], full.get(0, 0)[0]));
                return new AlignmentResult(
                        shiftXPx * pixelsToMm,
                        shiftYPx * pixelsToMm,
                        rotationDeg,
                        full
                );
            } finally {
                release(src, dst, inliersMask, scaledHomography);
            }
        } finally {
            release(curGray, curGrayScaled, curDescriptors);
            release(curKeypoints);
        }
    }

    private List<DMatch> ratioTestMatches(Mat refDescriptors, Mat curDescriptors, float ratio) {
        List<MatOfDMatch> knn = new ArrayList<>();
        matcher.knnMatch(refDescriptors, curDescriptors, knn, 2);
        List<DMatch> good = new ArrayList<>();
        for (MatOfDMatch matOfDMatch : knn) {
            DMatch[] arr = matOfDMatch.toArray();
            if (arr.length >= 2 && arr[0].distance < ratio * arr[1].distance) {
                good.add(arr[0]);
            }
            matOfDMatch.release();
        }
        return good;
    }

    private PreparedReference getOrBuildPreparedReference(Mat reference, String referenceCacheKey) {
        PreparedReference cached = preparedReferenceCache;
        if (cached != null && matchesPreparedReference(cached, referenceCacheKey, reference)) {
            return cached;
        }
        PreparedReference next = buildPreparedReference(reference, referenceCacheKey);
        if (preparedReferenceCache != null) {
            preparedReferenceCache.descriptors.release();
        }
        preparedReferenceCache = next;
        return next;
    }

    private static boolean matchesPreparedReference(
            PreparedReference cached,
            String referenceCacheKey,
            Mat reference
    ) {
        if (referenceCacheKey != null && !referenceCacheKey.isBlank()) {
            return referenceCacheKey.equals(cached.cacheKey);
        }
        return cached.dataAddr == reference.dataAddr()
                && cached.rows == reference.rows()
                && cached.cols == reference.cols();
    }

    private PreparedReference buildPreparedReference(Mat reference, String referenceCacheKey) {
        Mat gray = new Mat();
        Mat grayScaled = null;
        Mat descriptors = new Mat();
        MatOfKeyPoint keypoints = new MatOfKeyPoint();
        try {
            Imgproc.cvtColor(reference, gray, Imgproc.COLOR_BGR2GRAY);
            ResizeResult resized = resizeForProcessing(gray, MAX_ALIGNMENT_DIM);
            grayScaled = resized.mat;
            orb.detectAndCompute(grayScaled, new Mat(), keypoints, descriptors);
            return new PreparedReference(
                    referenceCacheKey,
                    reference.dataAddr(),
                    reference.rows(),
                    reference.cols(),
                    resized.scaleX,
                    resized.scaleY,
                    keypoints.toArray(),
                    descriptors.clone()
            );
        } finally {
            release(gray, grayScaled, descriptors);
            release(keypoints);
        }
    }

    private Mat warpCurrentToReference(Mat current, Mat homographyRefToCurrent) {
        Mat inverse = new Mat();
        Mat aligned = new Mat();
        try {
            Core.invert(homographyRefToCurrent, inverse);
            Imgproc.warpPerspective(
                    current,
                    aligned,
                    inverse,
                    current.size(),
                    Imgproc.INTER_LINEAR,
                    Core.BORDER_REPLICATE
            );
            return aligned;
        } finally {
            release(inverse);
        }
    }

    private Mat refineWithEcc(Mat aligned, Mat reference, Rect refineRect, List<NormPoint> polygon) {
        Mat refGray = new Mat();
        Mat curGray = new Mat();
        Mat refRoi = null;
        Mat curRoi = null;
        Mat mask = null;
        Mat warp = Mat.eye(2, 3, CvType.CV_32F);
        Mat emptyMask = null;
        try {
            Imgproc.cvtColor(reference, refGray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.cvtColor(aligned, curGray, Imgproc.COLOR_BGR2GRAY);
            Rect safe = toSafeRect(
                    new RoiRect(refineRect.x, refineRect.y, refineRect.width, refineRect.height),
                    reference.cols(),
                    reference.rows()
            );
            refRoi = new Mat(refGray, safe);
            curRoi = new Mat(curGray, safe);
            if (polygon != null && polygon.size() >= 3) {
                mask = RoiPolygonMask.maskForRect(polygon, safe, reference.cols(), reference.rows());
            }

            TermCriteria criteria = new TermCriteria(
                    TermCriteria.COUNT + TermCriteria.EPS,
                    50,
                    1e-4
            );
            emptyMask = mask == null ? new Mat() : null;
            double cc = Video.findTransformECC(
                    refRoi,
                    curRoi,
                    warp,
                    Video.MOTION_EUCLIDEAN,
                    criteria,
                    mask == null ? emptyMask : mask
            );
            if (!Double.isFinite(cc) || cc < 0.1) {
                return aligned;
            }

            Mat refined = new Mat();
            try {
                Imgproc.warpAffine(
                        aligned,
                        refined,
                        warp,
                        aligned.size(),
                        Imgproc.INTER_LINEAR,
                        Core.BORDER_REPLICATE
                );
                return refined;
            } catch (Exception e) {
                refined.release();
                return aligned;
            }
        } catch (Exception ignored) {
            return aligned;
        } finally {
            release(refGray, curGray, refRoi, curRoi, mask, warp, emptyMask);
        }
    }

    private Mat toOriginalScaleHomography(Mat scaledHomography, double scaleX, double scaleY) {
        if (Math.abs(scaleX - 1.0) < 1e-9 && Math.abs(scaleY - 1.0) < 1e-9) {
            return scaledHomography.clone();
        }
        Mat scaleToSmall = Mat.eye(3, 3, CvType.CV_64F);
        Mat scaleToOriginal = Mat.eye(3, 3, CvType.CV_64F);
        Mat empty = new Mat();
        Mat tmp = new Mat();
        Mat result = new Mat();
        try {
            scaleToSmall.put(0, 0, scaleX);
            scaleToSmall.put(1, 1, scaleY);
            scaleToOriginal.put(0, 0, 1.0 / scaleX);
            scaleToOriginal.put(1, 1, 1.0 / scaleY);
            Core.gemm(scaledHomography, scaleToSmall, 1.0, empty, 0.0, tmp);
            Core.gemm(scaleToOriginal, tmp, 1.0, empty, 0.0, result);
            return result;
        } finally {
            release(scaleToSmall, scaleToOriginal, empty, tmp);
        }
    }

    private ResizeResult resizeForProcessing(Mat src, int maxDim) {
        int longest = Math.max(src.cols(), src.rows());
        if (longest <= maxDim) {
            return new ResizeResult(src.clone(), 1.0, 1.0);
        }
        double scale = maxDim / (double) longest;
        Mat resized = new Mat();
        Imgproc.resize(src, resized, new Size(), scale, scale, Imgproc.INTER_AREA);
        return new ResizeResult(resized, scale, scale);
    }

    private Rect resolveMainRect(PositioningRequest request, int frameWidth, int frameHeight) {
        List<NormPoint> poly = request.mainRoiPolygonNorm();
        if (poly != null && poly.size() >= 3) {
            Rect fromPoly = RoiPolygonMask.boundingRect(poly, frameWidth, frameHeight);
            return toSafeRect(
                    new RoiRect(fromPoly.x, fromPoly.y, fromPoly.width, fromPoly.height),
                    frameWidth,
                    frameHeight
            );
        }
        return toSafeRect(request.mainRoi(), frameWidth, frameHeight);
    }

    private Rect toSafeRect(RoiRect roi, int frameWidth, int frameHeight) {
        RoiRect safe = roi == null ? new RoiRect(0, 0, frameWidth, frameHeight) : roi;
        int x = clamp(safe.x(), 0, Math.max(0, frameWidth - 1));
        int y = clamp(safe.y(), 0, Math.max(0, frameHeight - 1));
        int w = clamp(safe.width(), 1, frameWidth - x);
        int h = clamp(safe.height(), 1, frameHeight - y);
        return new Rect(x, y, w, h);
    }

    private AlignmentResult failedAlignment() {
        return new AlignmentResult(
                FALLBACK_FAIL_MM,
                FALLBACK_FAIL_MM,
                FALLBACK_FAIL_ROTATION_DEG,
                new Mat()
        );
    }

    private double[] homographyToArray(Mat homography) {
        if (homography == null || homography.empty() || homography.rows() != 3 || homography.cols() != 3) {
            return new double[0];
        }
        double[] flat = new double[9];
        int idx = 0;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                double[] v = homography.get(r, c);
                flat[idx++] = (v == null || v.length == 0) ? 0.0 : v[0];
            }
        }
        return flat;
    }

    private void validateInputFrames(Mat reference, Mat current) {
        if (reference == null || reference.empty() || current == null || current.empty()) {
            throw new IllegalArgumentException("reference/current frames must be non-empty");
        }
        if (reference.cols() != current.cols() || reference.rows() != current.rows()) {
            throw new IllegalArgumentException("reference and current frames must have the same size");
        }
    }

    private static double nanosToMs(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos) + (nanos % 1_000_000L) / 1_000_000.0;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void release(Mat... mats) {
        if (mats == null) {
            return;
        }
        for (Mat mat : mats) {
            if (mat != null) {
                mat.release();
            }
        }
    }

    private static void release(MatOfKeyPoint keypoints) {
        if (keypoints != null) {
            keypoints.release();
        }
    }

    private record AlignmentResult(double shiftXmm, double shiftYmm, double rotationDeg, Mat homographyRefToCurrent) {
    }

    private record ResizeResult(Mat mat, double scaleX, double scaleY) {
    }

    private record PreparedReference(
            String cacheKey,
            long dataAddr,
            int rows,
            int cols,
            double scaleX,
            double scaleY,
            KeyPoint[] keypoints,
            Mat descriptors
    ) {
    }
}
