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
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;
import org.opencv.video.Video;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Lock current frame into the reference pose.
 *
 * Pipeline (same idea as analisSurface):
 * 1) phaseCorrelate coarse translation
 * 2) ORB+RANSAC homography current→reference (full frame)
 * 3) pyramid ECC affine with WARP_INVERSE_MAP (critical)
 */
public final class BucketPositioningService {

    private static final int MAX_ORB_DIM = 1024;
    private static final int ORB_FEATURES = 6000;
    private static final double RANSAC_REPROJ_THRESHOLD = 5.0;
    private static final int MIN_MATCHES = 12;
    private static final int ECC_LEVELS = 4;
    private static final double FALLBACK_FAIL_MM = 9999.0;
    private static final double FALLBACK_FAIL_ROTATION_DEG = 9999.0;

    private final ShmMatWriter shmWriter;
    private final ORB orb;
    private final BFMatcher matcher;
    private final CLAHE clahe;
    private PreparedReference preparedReferenceCache;

    public BucketPositioningService(ShmMatWriter shmWriter) {
        this.shmWriter = shmWriter;
        this.orb = ORB.create(ORB_FEATURES);
        this.matcher = BFMatcher.create(Core.NORM_HAMMING, false);
        this.clahe = Imgproc.createCLAHE(2.0, new Size(8, 8));
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
        Mat working = null;
        Mat homographyCurToRef = null;
        try {
            validateInputFrames(reference, current);

            // --- 1) Coarse translation via phase correlation ---
            long tOrb0 = System.nanoTime();
            Point coarseShift = estimateCoarseShift(reference, current);
            Mat afterCoarse = applyTranslation(current, coarseShift.x, coarseShift.y);

            // --- 2) ORB homography: current → reference (like analisSurface) ---
            Mat orbH = estimateHomographyCurrentToReference(
                    reference, afterCoarse, referenceCacheKey);
            stageMsOrb = nanosToMs(System.nanoTime() - tOrb0);

            long tWarp0 = System.nanoTime();
            if (orbH != null && !orbH.empty()) {
                working = new Mat();
                Imgproc.warpPerspective(
                        afterCoarse,
                        working,
                        orbH,
                        current.size(),
                        Imgproc.INTER_LINEAR,
                        Core.BORDER_REPLICATE
                );
                homographyCurToRef = composeCurToRef(coarseShift, orbH);
            } else {
                working = afterCoarse;
                afterCoarse = null; // ownership moved
                homographyCurToRef = translationHomography(coarseShift.x, coarseShift.y);
            }
            stageMsWarp = nanosToMs(System.nanoTime() - tWarp0);
            release(afterCoarse, orbH);

            boolean matched = homographyCurToRef != null && !homographyCurToRef.empty();
            AlignmentMetrics metrics = metricsFromHomography(homographyCurToRef, request.pixelsToMm());

            // --- 3) Pyramid ECC affine with WARP_INVERSE_MAP ---
            long tEcc0 = System.nanoTime();
            Rect refineRect = expandRect(
                    resolveMainRect(request, current.cols(), current.rows()),
                    current.cols(),
                    current.rows(),
                    0.15
            );
            Mat refined = refinePyramidEcc(working, reference, refineRect, request.mainRoiPolygonNorm());
            stageMsEcc = nanosToMs(System.nanoTime() - tEcc0);
            if (refined != working) {
                working.release();
                working = refined;
            }

            boolean withinSoftTolerance = matched
                    && Math.abs(metrics.shiftXmm) <= request.maxShiftMm()
                    && Math.abs(metrics.shiftYmm) <= request.maxShiftMm()
                    && Math.abs(metrics.rotationDeg) <= request.maxRotationDeg();

            boolean alignedWritten = false;
            String outputName = request.outputShmName() == null ? "" : request.outputShmName().trim();
            if (request.writeAligned() && !outputName.isEmpty() && working != null && !working.empty()) {
                long tWrite0 = System.nanoTime();
                shmWriter.writeBgrMat(outputName, working);
                stageMsWrite = nanosToMs(System.nanoTime() - tWrite0);
                alignedWritten = true;
            }

            // For downstream "homographyRefToCurrent" consumers: invert cur→ref.
            double[] hRefToCur = invertHomographyArray(homographyCurToRef);

            boolean overallPass = request.writeAligned() ? alignedWritten : matched;
            if (request.writeAligned() && outputName.isEmpty()) {
                overallPass = false;
            }

            return new PositioningResponse(
                    metrics.shiftXmm,
                    metrics.shiftYmm,
                    metrics.rotationDeg,
                    hRefToCur,
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
                    nanosToMs(System.nanoTime() - tTotal0)
            );
        } finally {
            release(working);
            if (homographyCurToRef != null) {
                homographyCurToRef.release();
            }
        }
    }

    private Point estimateCoarseShift(Mat reference, Mat current) {
        Mat refGray = new Mat();
        Mat curGray = new Mat();
        Mat ref32 = new Mat();
        Mat cur32 = new Mat();
        Mat refSmall = null;
        Mat curSmall = null;
        try {
            Imgproc.cvtColor(reference, refGray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.cvtColor(current, curGray, Imgproc.COLOR_BGR2GRAY);
            clahe.apply(refGray, refGray);
            clahe.apply(curGray, curGray);
            ResizeResult r = resizeForProcessing(refGray, 512);
            ResizeResult c = resizeForProcessing(curGray, 512);
            refSmall = r.mat;
            curSmall = c.mat;
            refSmall.convertTo(ref32, CvType.CV_32F);
            curSmall.convertTo(cur32, CvType.CV_32F);
            Point shiftSmall = Core.phaseCorrelate(ref32, cur32);
            // phaseCorrelate(ref, cur): shift to apply to cur to match ref, in small-pixel units.
            return new Point(shiftSmall.x / c.scaleX, shiftSmall.y / c.scaleY);
        } catch (Exception e) {
            return new Point(0, 0);
        } finally {
            release(refGray, curGray, ref32, cur32, refSmall, curSmall);
        }
    }

    private Mat applyTranslation(Mat current, double dx, double dy) {
        Mat warp = new Mat(2, 3, CvType.CV_32F);
        Mat out = new Mat();
        try {
            // phaseCorrelate(ref,cur)=(dx,dy): shift to apply to cur (OpenCV samples / tutorial form).
            warp.put(0, 0, 1, 0, dx);
            warp.put(1, 0, 0, 1, dy);
            Imgproc.warpAffine(
                    current,
                    out,
                    warp,
                    current.size(),
                    Imgproc.INTER_LINEAR,
                    Core.BORDER_REPLICATE
            );
            return out;
        } finally {
            release(warp);
        }
    }

    private Mat estimateHomographyCurrentToReference(Mat reference, Mat current, String referenceCacheKey) {
        Mat curGray = new Mat();
        Mat curScaled = null;
        Mat curDescriptors = new Mat();
        MatOfKeyPoint curKeypoints = new MatOfKeyPoint();
        try {
            PreparedReference prepared = getOrBuildPreparedReference(reference, referenceCacheKey);
            Imgproc.cvtColor(current, curGray, Imgproc.COLOR_BGR2GRAY);
            clahe.apply(curGray, curGray);
            ResizeResult curResize = resizeForProcessing(curGray, MAX_ORB_DIM);
            curScaled = curResize.mat;

            orb.detectAndCompute(curScaled, new Mat(), curKeypoints, curDescriptors);
            if (prepared.descriptors.empty() || curDescriptors.empty()) {
                return new Mat();
            }

            // Match current → reference (query=cur, train=ref), like analisSurface.
            List<DMatch> good = ratioTestMatches(curDescriptors, prepared.descriptors, 0.80f);
            if (good.size() < MIN_MATCHES) {
                good = ratioTestMatches(curDescriptors, prepared.descriptors, 0.90f);
            }
            if (good.size() < MIN_MATCHES) {
                return new Mat();
            }

            KeyPoint[] curPts = curKeypoints.toArray();
            KeyPoint[] refPts = prepared.keypoints;
            List<Point> srcCur = new ArrayList<>(good.size());
            List<Point> dstRef = new ArrayList<>(good.size());
            for (DMatch m : good) {
                if (m.queryIdx < 0 || m.queryIdx >= curPts.length
                        || m.trainIdx < 0 || m.trainIdx >= refPts.length) {
                    continue;
                }
                srcCur.add(curPts[m.queryIdx].pt);
                dstRef.add(refPts[m.trainIdx].pt);
            }
            if (srcCur.size() < MIN_MATCHES) {
                return new Mat();
            }

            MatOfPoint2f src = new MatOfPoint2f();
            MatOfPoint2f dst = new MatOfPoint2f();
            Mat mask = new Mat();
            Mat hScaled = new Mat();
            try {
                src.fromList(srcCur);
                dst.fromList(dstRef);
                hScaled = Calib3d.findHomography(src, dst, Calib3d.RANSAC, RANSAC_REPROJ_THRESHOLD, mask);
                if (hScaled.empty() || Core.countNonZero(mask) < MIN_MATCHES) {
                    return new Mat();
                }
                // Points were in scaled space with same scale for ref&cur (same frame size).
                return toOriginalScaleHomography(hScaled, prepared.scaleX, prepared.scaleY);
            } finally {
                release(src, dst, mask, hScaled);
            }
        } finally {
            release(curGray, curScaled, curDescriptors);
            release(curKeypoints);
        }
    }

    /**
     * Pyramid ECC affine; matrix from findTransformECC must be applied with WARP_INVERSE_MAP.
     */
    private Mat refinePyramidEcc(Mat aligned, Mat reference, Rect refineRect, List<NormPoint> polygon) {
        Mat refGrayFull = new Mat();
        Mat curGrayFull = new Mat();
        Mat refRoi = null;
        Mat curRoi = null;
        Mat mask = null;
        try {
            Imgproc.cvtColor(reference, refGrayFull, Imgproc.COLOR_BGR2GRAY);
            Imgproc.cvtColor(aligned, curGrayFull, Imgproc.COLOR_BGR2GRAY);
            clahe.apply(refGrayFull, refGrayFull);
            clahe.apply(curGrayFull, curGrayFull);

            Rect safe = toSafeRect(
                    new RoiRect(refineRect.x, refineRect.y, refineRect.width, refineRect.height),
                    reference.cols(),
                    reference.rows()
            );
            refRoi = new Mat(refGrayFull, safe).clone();
            curRoi = new Mat(curGrayFull, safe).clone();
            if (polygon != null && polygon.size() >= 3) {
                mask = RoiPolygonMask.maskForRect(polygon, safe, reference.cols(), reference.rows());
            }

            List<Mat> refPyr = buildPyramid(refRoi, ECC_LEVELS);
            List<Mat> curPyr = buildPyramid(curRoi, ECC_LEVELS);
            Mat warp = Mat.eye(2, 3, CvType.CV_32F);
            TermCriteria criteria = new TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 60, 1e-5);
            try {
                for (int level = ECC_LEVELS - 1; level >= 0; level--) {
                    Mat refLvl = refPyr.get(level);
                    Mat curLvl = curPyr.get(level);
                    if (level < ECC_LEVELS - 1) {
                        double[] t0 = warp.get(0, 2);
                        double[] t1 = warp.get(1, 2);
                        warp.put(0, 2, t0[0] * 2.0);
                        warp.put(1, 2, t1[0] * 2.0);
                    }
                    try {
                        Mat ref32 = new Mat();
                        Mat cur32 = new Mat();
                        refLvl.convertTo(ref32, CvType.CV_32F);
                        curLvl.convertTo(cur32, CvType.CV_32F);
                        Point shift = Core.phaseCorrelate(ref32, cur32);
                        release(ref32, cur32);
                        double[] tx = warp.get(0, 2);
                        double[] ty = warp.get(1, 2);
                        warp.put(0, 2, tx[0] + shift.x);
                        warp.put(1, 2, ty[0] + shift.y);
                    } catch (Exception ignored) {
                        // keep previous warp
                    }

                    Mat levelMask = null;
                    try {
                        if (mask != null && level == 0) {
                            levelMask = mask;
                        } else if (mask != null) {
                            levelMask = new Mat();
                            Imgproc.resize(mask, levelMask, refLvl.size(), 0, 0, Imgproc.INTER_NEAREST);
                        } else {
                            levelMask = new Mat();
                        }
                        Video.findTransformECC(
                                refLvl,
                                curLvl,
                                warp,
                                Video.MOTION_AFFINE,
                                criteria,
                                levelMask
                        );
                    } catch (Exception ignored) {
                        // continue with last good warp
                    } finally {
                        if (levelMask != null && levelMask != mask) {
                            levelMask.release();
                        }
                    }
                }

                Mat refined = new Mat();
                // findTransformECC warp maps template←input; must use WARP_INVERSE_MAP.
                Imgproc.warpAffine(
                        aligned,
                        refined,
                        warp,
                        aligned.size(),
                        Imgproc.INTER_LINEAR | Imgproc.WARP_INVERSE_MAP,
                        Core.BORDER_REPLICATE
                );
                return refined;
            } finally {
                release(warp);
                releaseAll(refPyr);
                releaseAll(curPyr);
            }
        } catch (Exception e) {
            return aligned;
        } finally {
            release(refGrayFull, curGrayFull, refRoi, curRoi, mask);
        }
    }

    private List<Mat> buildPyramid(Mat src, int levels) {
        List<Mat> pyr = new ArrayList<>(levels);
        pyr.add(src.clone());
        for (int i = 1; i < levels; i++) {
            Mat down = new Mat();
            Imgproc.pyrDown(pyr.get(i - 1), down);
            pyr.add(down);
        }
        return pyr;
    }

    private List<DMatch> ratioTestMatches(Mat queryDescriptors, Mat trainDescriptors, float ratio) {
        List<MatOfDMatch> knn = new ArrayList<>();
        matcher.knnMatch(queryDescriptors, trainDescriptors, knn, 2);
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
        Mat scaled = null;
        Mat descriptors = new Mat();
        MatOfKeyPoint keypoints = new MatOfKeyPoint();
        try {
            Imgproc.cvtColor(reference, gray, Imgproc.COLOR_BGR2GRAY);
            clahe.apply(gray, gray);
            ResizeResult resized = resizeForProcessing(gray, MAX_ORB_DIM);
            scaled = resized.mat;
            orb.detectAndCompute(scaled, new Mat(), keypoints, descriptors);
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
            release(gray, scaled, descriptors);
            release(keypoints);
        }
    }

    private Mat composeCurToRef(Point coarseShift, Mat orbHCurToRef) {
        Mat t = translationHomography(coarseShift.x, coarseShift.y);
        Mat empty = new Mat();
        Mat composed = new Mat();
        try {
            // Apply coarse first (on original current), then ORB on result:
            // p_ref = H_orb * T * p_cur  →  H = H_orb * T
            Core.gemm(orbHCurToRef, t, 1.0, empty, 0.0, composed);
            return composed.clone();
        } finally {
            release(t, empty, composed);
        }
    }

    private Mat translationHomography(double dx, double dy) {
        // With WARP_INVERSE_MAP convention used for translation applyTranslation,
        // H that maps cur→ref for free warpPerspective (no inverse) is translate by (dx,dy):
        Mat h = Mat.eye(3, 3, CvType.CV_64F);
        h.put(0, 2, dx);
        h.put(1, 2, dy);
        return h;
    }

    private AlignmentMetrics metricsFromHomography(Mat hCurToRef, double pixelsToMm) {
        if (hCurToRef == null || hCurToRef.empty()) {
            return new AlignmentMetrics(FALLBACK_FAIL_MM, FALLBACK_FAIL_MM, FALLBACK_FAIL_ROTATION_DEG);
        }
        Mat inv = new Mat();
        try {
            Core.invert(hCurToRef, inv);
            double shiftXPx = inv.get(0, 2)[0];
            double shiftYPx = inv.get(1, 2)[0];
            double rotationDeg = Math.toDegrees(Math.atan2(inv.get(1, 0)[0], inv.get(0, 0)[0]));
            return new AlignmentMetrics(shiftXPx * pixelsToMm, shiftYPx * pixelsToMm, rotationDeg);
        } catch (Exception e) {
            return new AlignmentMetrics(FALLBACK_FAIL_MM, FALLBACK_FAIL_MM, FALLBACK_FAIL_ROTATION_DEG);
        } finally {
            release(inv);
        }
    }

    private double[] invertHomographyArray(Mat hCurToRef) {
        if (hCurToRef == null || hCurToRef.empty()) {
            return new double[0];
        }
        Mat inv = new Mat();
        try {
            Core.invert(hCurToRef, inv);
            return homographyToArray(inv);
        } catch (Exception e) {
            return new double[0];
        } finally {
            release(inv);
        }
    }

    private Mat toOriginalScaleHomography(Mat scaledHomography, double scaleX, double scaleY) {
        if (Math.abs(scaleX - 1.0) < 1e-9 && Math.abs(scaleY - 1.0) < 1e-9) {
            return scaledHomography.clone();
        }
        // H_scaled maps cur_scaled→ref_scaled; with p_s = S*p_full:
        // S * p_ref = H_scaled * S * p_cur  →  H_full = S_inv * H_scaled * S
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

    private Rect expandRect(Rect rect, int frameW, int frameH, double padFraction) {
        int padX = (int) Math.round(rect.width * padFraction);
        int padY = (int) Math.round(rect.height * padFraction);
        int x = clamp(rect.x - padX, 0, frameW - 1);
        int y = clamp(rect.y - padY, 0, frameH - 1);
        int x2 = clamp(rect.x + rect.width + padX, x + 1, frameW);
        int y2 = clamp(rect.y + rect.height + padY, y + 1, frameH);
        return new Rect(x, y, x2 - x, y2 - y);
    }

    private Rect toSafeRect(RoiRect roi, int frameWidth, int frameHeight) {
        RoiRect safe = roi == null ? new RoiRect(0, 0, frameWidth, frameHeight) : roi;
        int x = clamp(safe.x(), 0, Math.max(0, frameWidth - 1));
        int y = clamp(safe.y(), 0, Math.max(0, frameHeight - 1));
        int w = clamp(safe.width(), 1, frameWidth - x);
        int h = clamp(safe.height(), 1, frameHeight - y);
        return new Rect(x, y, w, h);
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

    private static void releaseAll(List<Mat> mats) {
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

    private record AlignmentMetrics(double shiftXmm, double shiftYmm, double rotationDeg) {
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
