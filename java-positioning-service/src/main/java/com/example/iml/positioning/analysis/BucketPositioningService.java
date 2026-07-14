package com.example.iml.positioning.analysis;

import com.example.iml.positioning.dto.NormPoint;
import com.example.iml.positioning.dto.PositioningRequest;
import com.example.iml.positioning.dto.PositioningResponse;
import com.example.iml.positioning.dto.RoiRect;
import com.example.iml.positioning.shm.ShmMatWriter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.core.TermCriteria;
import org.opencv.features2d.BFMatcher;
import org.opencv.features2d.ORB;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;
import org.opencv.video.Video;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Lock current frame into the reference pose with a rigid transform only
 * Primary: translation. Tiny rotation only (conveyor); fake ORB twist is clamped off.
 * No perspective / scale / shear — those stretch the bucket.
 *
 * Pipeline:
 * 1) phaseCorrelate coarse translation
 * 2) ORB + estimateAffinePartial2D (angle clamped ≤0.35°) current→reference
 * 3) pyramid ECC MOTION_TRANSLATION refine (optional)
 */
public final class BucketPositioningService {

    private static final Logger log = LogManager.getLogger(BucketPositioningService.class);

    private static final int MAX_ORB_DIM = 1024;
    private static final int ORB_FEATURES = 6000;
    private static final double RANSAC_REPROJ_THRESHOLD = 5.0;
    private static final int MIN_MATCHES = 12;
    private static final int ECC_LEVELS = 4;
    /**
     * Conveyor buckets barely rotate; ORB often invents 1–3° twist from background matches.
     * Anything larger is forced to pure translation.
     */
    private static final double ORB_MAX_ANGLE_DEG = 0.35;
    /** After ORB, ECC may only refine within these bounds — larger = garbage / divergence. */
    private static final double ECC_MAX_TRANSLATION_PX = 128.0;
    private static final double ECC_MAX_ANGLE_DEG = 0.35;
    private static final double ECC_SKIP_NCC = 0.94;
    private static final double ECC_SKIP_ABSDiff = 2.5;
    private static final double ECC_SKIP_RESIDUAL_PX = 1.0;
    private static final double RESIDUAL_POLISH_MIN_PX = 2.0;
    private static final double RESIDUAL_POLISH_MAX_PX = 180.0;
    /** Soft gate: both residual + absdiff still bad → refuse PASS (cam=2/4 leftover shift). */
    private static final double ALIGN_FAIL_RESIDUAL_PX = 10.0;
    private static final double ALIGN_FAIL_ABSDiff = 10.0;
    /** Hard gate: absdiff alone (residual can read ~0 while frame is still wrong — cam=0). */
    private static final double ALIGN_FAIL_ABSDiff_HARD = 16.0;
    private static final int ORB_MIN_REF_KEYPOINTS = 48;
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
        return position(reference, current, request, referenceCacheKey, Map.of());
    }

    public PositioningResponse position(
            Mat reference,
            Mat current,
            PositioningRequest request,
            String referenceCacheKey,
            Map<String, Object> logContext
    ) {
        long tTotal0 = System.nanoTime();
        double stageMsOrb = 0;
        double stageMsWarp = 0;
        double stageMsEcc = 0;
        double stageMsWrite = 0;
        Mat working = null;
        Mat homographyCurToRef = null;
        QualityScore qOrbHold = null;
        Map<String, Object> diag = new LinkedHashMap<>();
        try {
            validateInputFrames(reference, current);
            Rect qualityRoi = expandRect(
                    resolveMainRect(request, current.cols(), current.rows()),
                    current.cols(),
                    current.rows(),
                    0.05
            );
            diag.put("frame_w", current.cols());
            diag.put("frame_h", current.rows());
            diag.put("roi_x", qualityRoi.x);
            diag.put("roi_y", qualityRoi.y);
            diag.put("roi_w", qualityRoi.width);
            diag.put("roi_h", qualityRoi.height);
            diag.put("ref_cache_key", referenceCacheKey == null ? "" : referenceCacheKey);

            QualityScore q0 = measureQuality(reference, current, qualityRoi, request.mainRoiPolygonNorm());
            putQuality(diag, "raw", q0);
            log.info(
                    "positioning_diag {} stage=raw mean_absdiff={} ncc={} residual_shift=({}, {}) px",
                    ctx(logContext),
                    fmt(q0.meanAbsDiff()),
                    fmt(q0.ncc()),
                    fmt(q0.residualShiftX()),
                    fmt(q0.residualShiftY())
            );

            // --- 1) Coarse translation via phase correlation (ROI only) ---
            long tOrb0 = System.nanoTime();
            Point coarseShift = estimateCoarseShift(reference, current, qualityRoi, request.mainRoiPolygonNorm());
            diag.put("coarse_dx_px", coarseShift.x);
            diag.put("coarse_dy_px", coarseShift.y);
            Mat afterCoarse = applyTranslation(current, coarseShift.x, coarseShift.y);
            QualityScore qCoarse = measureQuality(reference, afterCoarse, qualityRoi, request.mainRoiPolygonNorm());
            if (!isQualityImproved(q0, qCoarse)) {
                log.warn(
                        "positioning_diag {} stage=coarse REJECTED absdiff={}→{} residual={}→{}",
                        ctx(logContext),
                        fmt(q0.meanAbsDiff()),
                        fmt(qCoarse.meanAbsDiff()),
                        fmt(Math.hypot(q0.residualShiftX(), q0.residualShiftY())),
                        fmt(Math.hypot(qCoarse.residualShiftX(), qCoarse.residualShiftY()))
                );
                afterCoarse.release();
                afterCoarse = current.clone();
                coarseShift = new Point(0, 0);
                qCoarse = q0;
                diag.put("coarse_rejected", true);
                diag.put("coarse_dx_px", 0.0);
                diag.put("coarse_dy_px", 0.0);
                // Coarse FFT often fails but quality residual is still usable — try ±residual.
                ResidualPolish coarseRes = polishResidualTranslation(
                        reference, afterCoarse, null, qualityRoi, request.mainRoiPolygonNorm());
                if (coarseRes.applied()) {
                    afterCoarse.release();
                    afterCoarse = coarseRes.frame();
                    coarseShift = new Point(coarseRes.dx(), coarseRes.dy());
                    qCoarse = coarseRes.quality();
                    if (coarseRes.homography() != null) {
                        coarseRes.homography().release();
                    }
                    diag.put("coarse_residual_fallback", true);
                    diag.put("coarse_dx_px", coarseShift.x);
                    diag.put("coarse_dy_px", coarseShift.y);
                    log.info(
                            "positioning_diag {} stage=coarse_residual_fallback shift=({}, {}) px absdiff={} residual=({}, {})",
                            ctx(logContext),
                            fmt(coarseShift.x),
                            fmt(coarseShift.y),
                            fmt(qCoarse.meanAbsDiff()),
                            fmt(qCoarse.residualShiftX()),
                            fmt(qCoarse.residualShiftY())
                    );
                }
            }
            putQuality(diag, "coarse", qCoarse);
            log.info(
                    "positioning_diag {} stage=coarse shift=({}, {}) px mean_absdiff={} ncc={} residual_shift=({}, {}) px",
                    ctx(logContext),
                    fmt(coarseShift.x),
                    fmt(coarseShift.y),
                    fmt(qCoarse.meanAbsDiff()),
                    fmt(qCoarse.ncc()),
                    fmt(qCoarse.residualShiftX()),
                    fmt(qCoarse.residualShiftY())
            );

            // --- 2) ORB rigid (translate+rotate) inside interest ROI only ---
            Rect orbRoi = expandRect(
                    resolveMainRect(request, current.cols(), current.rows()),
                    current.cols(),
                    current.rows(),
                    0.40
            );
            String orbCacheKey = (referenceCacheKey == null ? "" : referenceCacheKey)
                    + "|orb=" + orbRoi.x + "," + orbRoi.y + "," + orbRoi.width + "," + orbRoi.height;
            OrbResult orbResult = estimateRigidTransformCurrentToReference(
                    reference, afterCoarse, orbCacheKey, orbRoi, request.mainRoiPolygonNorm());
            // Tight ROI mask can starve ORB (kp_ref=0). Retry full-frame features.
            if (orbResult.refKeypoints() < ORB_MIN_REF_KEYPOINTS
                    || orbResult.homography() == null
                    || orbResult.homography().empty()
                    || orbResult.inliers() < MIN_MATCHES) {
                String fullKey = (referenceCacheKey == null ? "" : referenceCacheKey) + "|orb=full";
                OrbResult fullOrb = estimateRigidTransformCurrentToReference(
                        reference, afterCoarse, fullKey, null, null);
                boolean orbEmpty = orbResult.homography() == null || orbResult.homography().empty();
                boolean fullOk = fullOrb.homography() != null && !fullOrb.homography().empty();
                boolean preferFull = fullOk && (orbEmpty
                        || orbResult.refKeypoints() < ORB_MIN_REF_KEYPOINTS
                        || orbResult.inliers() < MIN_MATCHES);
                if (preferFull) {
                    log.info(
                            "positioning_diag {} stage=orb FALLBACK_FULLFRAME kp_ref={}→{} good={}->{} inliers={}->{}",
                            ctx(logContext),
                            orbResult.refKeypoints(),
                            fullOrb.refKeypoints(),
                            orbResult.goodMatches(),
                            fullOrb.goodMatches(),
                            orbResult.inliers(),
                            fullOrb.inliers()
                    );
                    if (orbResult.homography() != null) {
                        orbResult.homography().release();
                    }
                    orbResult = fullOrb;
                    diag.put("orb_fullframe_fallback", true);
                } else if (fullOrb.homography() != null) {
                    fullOrb.homography().release();
                }
            }
            stageMsOrb = nanosToMs(System.nanoTime() - tOrb0);
            diag.put("orb_ref_keypoints", orbResult.refKeypoints());
            diag.put("orb_cur_keypoints", orbResult.curKeypoints());
            diag.put("orb_good_matches", orbResult.goodMatches());
            diag.put("orb_inliers", orbResult.inliers());
            diag.put("orb_ok", !orbResult.homography().empty());
            diag.put("orb_model", "euclidean");
            log.info(
                    "positioning_diag {} stage=orb model=euclidean kp_ref={} kp_cur={} good_matches={} inliers={} ok={}",
                    ctx(logContext),
                    orbResult.refKeypoints(),
                    orbResult.curKeypoints(),
                    orbResult.goodMatches(),
                    orbResult.inliers(),
                    !orbResult.homography().empty()
            );

            long tWarp0 = System.nanoTime();
            Mat orbH = orbResult.homography();
            QualityScore qBeforeOrb = qCoarse;
            if (orbH != null && !orbH.empty()) {
                Mat affine = null;
                Mat orbCandidate = null;
                try {
                    affine = homographyToAffine23(orbH);
                    orbCandidate = new Mat();
                    Imgproc.warpAffine(
                            afterCoarse,
                            orbCandidate,
                            affine,
                            current.size(),
                            Imgproc.INTER_LINEAR,
                            Core.BORDER_REPLICATE
                    );
                    QualityScore qCand = measureQuality(
                            reference, orbCandidate, qualityRoi, request.mainRoiPolygonNorm());
                    if (isQualityImproved(qBeforeOrb, qCand)
                            || isOrbResidualAcceptable(qBeforeOrb, qCand)) {
                        working = orbCandidate;
                        orbCandidate = null;
                        homographyCurToRef = composeCurToRef(coarseShift, orbH);
                        qOrbHold = qCand;
                    } else {
                        log.warn(
                                "positioning_diag {} stage=orb REJECTED_quality absdiff={}→{} residual={}→{}",
                                ctx(logContext),
                                fmt(qBeforeOrb.meanAbsDiff()),
                                fmt(qCand.meanAbsDiff()),
                                fmt(Math.hypot(qBeforeOrb.residualShiftX(), qBeforeOrb.residualShiftY())),
                                fmt(Math.hypot(qCand.residualShiftX(), qCand.residualShiftY()))
                        );
                        diag.put("orb_rejected_quality", true);
                        diag.put("orb_ok", false);
                        release(orbH);
                        orbH = new Mat();
                        working = afterCoarse;
                        afterCoarse = null;
                        homographyCurToRef = translationHomography(coarseShift.x, coarseShift.y);
                        qOrbHold = qBeforeOrb;
                    }
                } finally {
                    release(affine, orbCandidate);
                }
            } else {
                working = afterCoarse;
                afterCoarse = null;
                homographyCurToRef = translationHomography(coarseShift.x, coarseShift.y);
                qOrbHold = qBeforeOrb;
                log.warn("positioning_diag {} stage=orb FAILED — falling back to coarse translation only", ctx(logContext));
            }
            stageMsWarp = nanosToMs(System.nanoTime() - tWarp0);
            release(afterCoarse, orbH);

            boolean matched = homographyCurToRef != null && !homographyCurToRef.empty();
            QualityScore qOrb = qOrbHold != null
                    ? qOrbHold
                    : measureQuality(reference, working, qualityRoi, request.mainRoiPolygonNorm());

            // --- 2b) Residual translation polish (ORB often leaves a pure shift) ---
            ResidualPolish residualPolish = polishResidualTranslation(
                    reference, working, homographyCurToRef, qualityRoi, request.mainRoiPolygonNorm());
            if (residualPolish.applied()) {
                working.release();
                working = residualPolish.frame();
                if (homographyCurToRef != null) {
                    homographyCurToRef.release();
                }
                homographyCurToRef = residualPolish.homography();
                qOrb = residualPolish.quality();
                diag.put("residual_polish", true);
                diag.put("residual_polish_dx", residualPolish.dx());
                diag.put("residual_polish_dy", residualPolish.dy());
                log.info(
                        "positioning_diag {} stage=residual_polish shift=({}, {}) px mean_absdiff={} ncc={} residual_shift=({}, {}) px",
                        ctx(logContext),
                        fmt(residualPolish.dx()),
                        fmt(residualPolish.dy()),
                        fmt(qOrb.meanAbsDiff()),
                        fmt(qOrb.ncc()),
                        fmt(qOrb.residualShiftX()),
                        fmt(qOrb.residualShiftY())
                );
            } else {
                diag.put("residual_polish", false);
            }

            AlignmentMetrics metrics = metricsFromHomography(homographyCurToRef, request.pixelsToMm());
            putQuality(diag, "orb", qOrb);
            log.info(
                    "positioning_diag {} stage=after_orb shift_mm=({}, {}) rot_deg={} mean_absdiff={} ncc={} residual_shift=({}, {}) px",
                    ctx(logContext),
                    fmt(metrics.shiftXmm),
                    fmt(metrics.shiftYmm),
                    fmt(metrics.rotationDeg),
                    fmt(qOrb.meanAbsDiff()),
                    fmt(qOrb.ncc()),
                    fmt(qOrb.residualShiftX()),
                    fmt(qOrb.residualShiftY())
            );

            // --- 3) Pyramid ECC affine with WARP_INVERSE_MAP (optional refine) ---
            long tEcc0 = System.nanoTime();
            QualityScore qBeforeEcc = qOrb;
            boolean skipEcc = shouldSkipEcc(qBeforeEcc);
            diag.put("ecc_skipped", skipEcc);
            if (skipEcc) {
                stageMsEcc = nanosToMs(System.nanoTime() - tEcc0);
                diag.put("ecc_ok", false);
                diag.put("ecc_tx", 0.0);
                diag.put("ecc_ty", 0.0);
                diag.put("ecc_angle_deg", 0.0);
                diag.put("ecc_applied", false);
                log.info(
                        "positioning_diag {} stage=ecc SKIPPED already_good ncc={} absdiff={} residual=({}, {})",
                        ctx(logContext),
                        fmt(qBeforeEcc.ncc()),
                        fmt(qBeforeEcc.meanAbsDiff()),
                        fmt(qBeforeEcc.residualShiftX()),
                        fmt(qBeforeEcc.residualShiftY())
                );
            } else {
                Rect refineRect = expandRect(
                        resolveMainRect(request, current.cols(), current.rows()),
                        current.cols(),
                        current.rows(),
                        0.15
                );
                double residualMag = Math.hypot(qBeforeEcc.residualShiftX(), qBeforeEcc.residualShiftY());
                // Do not clamp ECC range by residual alone — phaseCorrelate can read ~0 while absdiff
                // is high (large true pose). Quality gate decides whether to keep the warp.
                double eccMaxTx = ECC_MAX_TRANSLATION_PX;
                if (Double.isFinite(qBeforeEcc.meanAbsDiff()) && qBeforeEcc.meanAbsDiff() > 10.0) {
                    eccMaxTx = Math.max(eccMaxTx, Math.min(160.0, residualMag + 80.0));
                }
                EccResult ecc = refinePyramidEcc(
                        working,
                        reference,
                        refineRect,
                        request.mainRoiPolygonNorm(),
                        qBeforeEcc.residualShiftX(),
                        qBeforeEcc.residualShiftY(),
                        eccMaxTx
                );
                stageMsEcc = nanosToMs(System.nanoTime() - tEcc0);
                diag.put("ecc_ok", ecc.ok());
                putFinite(diag, "ecc_cc", ecc.correlation());
                putFinite(diag, "ecc_tx", ecc.tx());
                putFinite(diag, "ecc_ty", ecc.ty());
                putFinite(diag, "ecc_angle_deg", ecc.angleDeg());

                boolean acceptEcc = false;
                if (ecc.refined() != working && ecc.ok() && isEccTransformPlausible(ecc, eccMaxTx)) {
                    QualityScore qEcc = measureQuality(
                            reference, ecc.refined(), qualityRoi, request.mainRoiPolygonNorm());
                    putQuality(diag, "ecc_try", qEcc);
                    acceptEcc = isEccQualityBetter(qBeforeEcc, qEcc);
                    diag.put("ecc_try_accepted", acceptEcc);
                    if (acceptEcc) {
                        working.release();
                        working = ecc.refined();
                        qBeforeEcc = qEcc;
                    } else {
                        ecc.refined().release();
                        log.warn(
                                "positioning_diag {} stage=ecc REJECTED_quality before_absdiff={} after_absdiff={} "
                                        + "before_ncc={} after_ncc={}",
                                ctx(logContext),
                                fmt(qOrb.meanAbsDiff()),
                                fmt(qEcc.meanAbsDiff()),
                                fmt(qOrb.ncc()),
                                fmt(qEcc.ncc())
                        );
                    }
                } else if (ecc.refined() != working) {
                    ecc.refined().release();
                    log.warn(
                            "positioning_diag {} stage=ecc REJECTED_transform ok={} t=({}, {}) angle={}",
                            ctx(logContext),
                            ecc.ok(),
                            fmt(ecc.tx()),
                            fmt(ecc.ty()),
                            fmt(ecc.angleDeg())
                    );
                }
                diag.put("ecc_applied", acceptEcc);
                log.info(
                        "positioning_diag {} stage=ecc ok={} applied={} cc={} affine_t=({}, {}) angle_deg={}",
                        ctx(logContext),
                        ecc.ok(),
                        acceptEcc,
                        fmt(ecc.correlation()),
                        fmt(ecc.tx()),
                        fmt(ecc.ty()),
                        fmt(ecc.angleDeg())
                );
            }

            // Final residual translation polish if ECC skipped / left a pure shift.
            ResidualPolish postEccPolish = polishResidualTranslation(
                    reference, working, homographyCurToRef, qualityRoi, request.mainRoiPolygonNorm());
            if (postEccPolish.applied()) {
                working.release();
                working = postEccPolish.frame();
                if (homographyCurToRef != null) {
                    homographyCurToRef.release();
                }
                homographyCurToRef = postEccPolish.homography();
                metrics = metricsFromHomography(homographyCurToRef, request.pixelsToMm());
                diag.put("post_ecc_residual_polish", true);
                log.info(
                        "positioning_diag {} stage=post_ecc_polish shift=({}, {}) px mean_absdiff={} ncc={} residual=({}, {})",
                        ctx(logContext),
                        fmt(postEccPolish.dx()),
                        fmt(postEccPolish.dy()),
                        fmt(postEccPolish.quality().meanAbsDiff()),
                        fmt(postEccPolish.quality().ncc()),
                        fmt(postEccPolish.quality().residualShiftX()),
                        fmt(postEccPolish.quality().residualShiftY())
                );
            }

            QualityScore qFinal = measureQuality(reference, working, qualityRoi, request.mainRoiPolygonNorm());
            putQuality(diag, "final", qFinal);
            log.info(
                    "positioning_diag {} stage=final mean_absdiff={} ncc={} residual_shift=({}, {}) px "
                            + "improvement_absdiff={} (raw→final)",
                    ctx(logContext),
                    fmt(qFinal.meanAbsDiff()),
                    fmt(qFinal.ncc()),
                    fmt(qFinal.residualShiftX()),
                    fmt(qFinal.residualShiftY()),
                    fmt(q0.meanAbsDiff() - qFinal.meanAbsDiff())
            );

            double[] hRefToCur = invertHomographyArray(homographyCurToRef);
            boolean alignedWritten = false;
            String outputName = request.outputShmName() == null ? "" : request.outputShmName().trim();
            String writeError = null;

            double finalResidual = Math.hypot(qFinal.residualShiftX(), qFinal.residualShiftY());
            // Residual metric can lie on striped texture; absdiff is the hard floor.
            boolean stillMisaligned = Double.isFinite(qFinal.meanAbsDiff())
                    && (qFinal.meanAbsDiff() >= ALIGN_FAIL_ABSDiff_HARD
                    || (qFinal.meanAbsDiff() >= ALIGN_FAIL_ABSDiff
                            && finalResidual >= ALIGN_FAIL_RESIDUAL_PX));
            diag.put("align_quality_ok", !stillMisaligned);
            if (stillMisaligned) {
                log.warn(
                        "positioning_diag {} stage=align_quality FAIL absdiff={} residual={} — refusing PASS",
                        ctx(logContext),
                        fmt(qFinal.meanAbsDiff()),
                        fmt(finalResidual)
                );
            }

            if (request.writeAligned() && !outputName.isEmpty() && working != null && !working.empty()) {
                long tWrite0 = System.nanoTime();
                try {
                    shmWriter.writeBgrMat(outputName, working);
                    alignedWritten = true;
                } catch (Exception e) {
                    writeError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    diag.put("write_error", writeError);
                    log.error(
                            "positioning_diag {} stage=write FAILED output={} err={}",
                            ctx(logContext),
                            outputName,
                            writeError
                    );
                }
                stageMsWrite = nanosToMs(System.nanoTime() - tWrite0);
            }

            boolean withinSoftTolerance = matched
                    && Math.abs(metrics.shiftXmm) <= request.maxShiftMm()
                    && Math.abs(metrics.shiftYmm) <= request.maxShiftMm()
                    && Math.abs(metrics.rotationDeg) <= request.maxRotationDeg();

            boolean overallPass = request.writeAligned()
                    ? (alignedWritten && !stillMisaligned)
                    : (matched && !stillMisaligned);
            if (request.writeAligned() && outputName.isEmpty()) {
                overallPass = false;
            }

            double stageMsTotal = nanosToMs(System.nanoTime() - tTotal0);
            diag.put("status", overallPass ? "PASS" : "FAIL");
            log.info(
                    "positioning_diag {} stage=summary status={} written={} total_ms={} orb_ms={} warp_ms={} ecc_ms={} write_ms={}",
                    ctx(logContext),
                    overallPass ? "PASS" : "FAIL",
                    alignedWritten,
                    fmt(stageMsTotal),
                    fmt(stageMsOrb),
                    fmt(stageMsWarp),
                    fmt(stageMsEcc),
                    fmt(stageMsWrite)
            );

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
                    stageMsTotal,
                    Map.copyOf(diag.isEmpty() ? Map.of() : stripNulls(diag))
            );
        } finally {
            release(working);
            if (homographyCurToRef != null) {
                homographyCurToRef.release();
            }
        }
    }

    private Point estimateCoarseShift(Mat reference, Mat current, Rect roi, List<NormPoint> polygon) {
        Mat refGray = new Mat();
        Mat curGray = new Mat();
        Mat ref32 = new Mat();
        Mat cur32 = new Mat();
        Mat refRoi = null;
        Mat curRoi = null;
        Mat mask = null;
        Mat refMasked = null;
        Mat curMasked = null;
        Mat refSmall = null;
        Mat curSmall = null;
        try {
            Imgproc.cvtColor(reference, refGray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.cvtColor(current, curGray, Imgproc.COLOR_BGR2GRAY);
            clahe.apply(refGray, refGray);
            clahe.apply(curGray, curGray);
            Rect safe = toSafeRect(
                    new RoiRect(roi.x, roi.y, roi.width, roi.height),
                    reference.cols(),
                    reference.rows()
            );
            refRoi = new Mat(refGray, safe).clone();
            curRoi = new Mat(curGray, safe).clone();
            if (polygon != null && polygon.size() >= 3) {
                mask = RoiPolygonMask.maskForRect(polygon, safe, reference.cols(), reference.rows());
                refMasked = new Mat();
                curMasked = new Mat();
                Core.bitwise_and(refRoi, refRoi, refMasked, mask);
                Core.bitwise_and(curRoi, curRoi, curMasked, mask);
            } else {
                refMasked = refRoi;
                refRoi = null;
                curMasked = curRoi;
                curRoi = null;
            }
            ResizeResult r = resizeForProcessing(refMasked, 512);
            ResizeResult c = resizeForProcessing(curMasked, 512);
            refSmall = r.mat;
            curSmall = c.mat;
            refSmall.convertTo(ref32, CvType.CV_32F);
            curSmall.convertTo(cur32, CvType.CV_32F);
            Point shiftSmall = Imgproc.phaseCorrelate(ref32, cur32);
            return new Point(shiftSmall.x / c.scaleX, shiftSmall.y / c.scaleY);
        } catch (Exception e) {
            return new Point(0, 0);
        } finally {
            release(refGray, curGray, ref32, cur32, refRoi, curRoi, mask, refMasked, curMasked, refSmall, curSmall);
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

    /**
     * Rigid pose (R + t) from ORB matches inside the interest ROI only.
     * Full projective homography is intentionally avoided — it stretches/shears the bucket.
     */
    private OrbResult estimateRigidTransformCurrentToReference(
            Mat reference,
            Mat current,
            String referenceCacheKey,
            Rect interestRoi,
            List<NormPoint> polygon
    ) {
        Mat curGray = new Mat();
        Mat curScaled = null;
        Mat curDescriptors = new Mat();
        MatOfKeyPoint curKeypoints = new MatOfKeyPoint();
        Mat interestMaskFull = null;
        Mat curMaskScaled = null;
        try {
            interestMaskFull = null;
            if (interestRoi != null) {
                interestMaskFull = RoiPolygonMask.maskForFrame(
                        polygon, interestRoi, reference.cols(), reference.rows());
            }
            PreparedReference prepared = getOrBuildPreparedReference(
                    reference, referenceCacheKey, interestMaskFull);
            Imgproc.cvtColor(current, curGray, Imgproc.COLOR_BGR2GRAY);
            clahe.apply(curGray, curGray);
            ResizeResult curResize = resizeForProcessing(curGray, MAX_ORB_DIM);
            curScaled = curResize.mat;
            if (interestMaskFull != null && !interestMaskFull.empty()) {
                curMaskScaled = new Mat();
                Imgproc.resize(
                        interestMaskFull,
                        curMaskScaled,
                        curScaled.size(),
                        0,
                        0,
                        Imgproc.INTER_NEAREST
                );
                orb.detectAndCompute(curScaled, curMaskScaled, curKeypoints, curDescriptors);
            } else {
                orb.detectAndCompute(curScaled, new Mat(), curKeypoints, curDescriptors);
            }
            int refKp = prepared.keypoints == null ? 0 : prepared.keypoints.length;
            int curKp = curKeypoints.toArray().length;
            if (prepared.descriptors.empty() || curDescriptors.empty()) {
                return new OrbResult(new Mat(), refKp, curKp, 0, 0);
            }

            List<DMatch> good = ratioTestMatches(curDescriptors, prepared.descriptors, 0.80f);
            float usedRatio = 0.80f;
            if (good.size() < MIN_MATCHES) {
                good = ratioTestMatches(curDescriptors, prepared.descriptors, 0.90f);
                usedRatio = 0.90f;
            }
            if (good.size() < MIN_MATCHES) {
                log.warn(
                        "positioning_diag stage=orb_match FAIL good_matches={} min={} ratio={}",
                        good.size(),
                        MIN_MATCHES,
                        usedRatio
                );
                return new OrbResult(new Mat(), refKp, curKp, good.size(), 0);
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
                return new OrbResult(new Mat(), refKp, curKp, good.size(), 0);
            }

            MatOfPoint2f src = new MatOfPoint2f();
            MatOfPoint2f dst = new MatOfPoint2f();
            Mat inliersMask = new Mat();
            Mat affineScaled = new Mat();
            try {
                src.fromList(srcCur);
                dst.fromList(dstRef);
                affineScaled = Calib3d.estimateAffinePartial2D(
                        src,
                        dst,
                        inliersMask,
                        Calib3d.RANSAC,
                        RANSAC_REPROJ_THRESHOLD,
                        2000,
                        0.99,
                        10
                );
                int inliers = inliersMask.empty() ? 0 : Core.countNonZero(inliersMask);
                if (affineScaled.empty() || inliers < MIN_MATCHES) {
                    log.warn(
                            "positioning_diag stage=orb_euclidean FAIL inliers={} good={} empty={}",
                            inliers,
                            srcCur.size(),
                            affineScaled.empty()
                    );
                    return new OrbResult(new Mat(), refKp, curKp, good.size(), inliers);
                }
                Mat hScaled = affine23ToHomography(affineScaled);
                try {
                    Mat full = toOriginalScaleHomography(hScaled, prepared.scaleX, prepared.scaleY);
                    Mat rigid = projectToEuclideanHomography(full);
                    release(full);
                    return new OrbResult(rigid, refKp, curKp, good.size(), inliers);
                } finally {
                    release(hScaled);
                }
            } finally {
                release(src, dst, inliersMask, affineScaled);
            }
        } finally {
            release(curGray, curScaled, curDescriptors, interestMaskFull, curMaskScaled);
            release(curKeypoints);
        }
    }

    private static Mat affine23ToHomography(Mat affine23) {
        Mat h = Mat.eye(3, 3, CvType.CV_64F);
        h.put(0, 0, affine23.get(0, 0)[0], affine23.get(0, 1)[0], affine23.get(0, 2)[0]);
        h.put(1, 0, affine23.get(1, 0)[0], affine23.get(1, 1)[0], affine23.get(1, 2)[0]);
        return h;
    }

    private static Mat homographyToAffine23(Mat h) {
        Mat a = new Mat(2, 3, CvType.CV_64F);
        a.put(0, 0, h.get(0, 0)[0], h.get(0, 1)[0], h.get(0, 2)[0]);
        a.put(1, 0, h.get(1, 0)[0], h.get(1, 1)[0], h.get(1, 2)[0]);
        return a;
    }

    /**
     * Keep translation (+ tiny rotation). Fake ORB twist above {@link #ORB_MAX_ANGLE_DEG}
     * is stripped so the bucket is not “подкручен” around Z.
     */
    private static Mat projectToEuclideanHomography(Mat h) {
        double a = h.get(0, 0)[0];
        double b = h.get(1, 0)[0];
        double angle = Math.atan2(b, a);
        double maxRad = Math.toRadians(ORB_MAX_ANGLE_DEG);
        if (Math.abs(angle) > maxRad) {
            log.info(
                    "positioning_diag stage=orb_angle CLAMPED {}→0 deg (conveyor translation-only)",
                    fmt(Math.toDegrees(angle))
            );
            angle = 0.0;
        }
        double c = Math.cos(angle);
        double s = Math.sin(angle);
        Mat out = Mat.eye(3, 3, CvType.CV_64F);
        out.put(0, 0, c, -s, h.get(0, 2)[0]);
        out.put(1, 0, s, c, h.get(1, 2)[0]);
        return out;
    }

    /**
     * Pyramid ECC affine; matrix from findTransformECC must be applied with WARP_INVERSE_MAP.
     * On level failure the previous warp is restored — OpenCV may leave a corrupted matrix.
     * If nothing converges, returns the input frame unchanged.
     */
    private EccResult refinePyramidEcc(
            Mat aligned,
            Mat reference,
            Rect refineRect,
            List<NormPoint> polygon,
            double seedTx,
            double seedTy,
            double maxTranslationPx
    ) {
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
            // Seed with residual phase shift (same units as phaseCorrelate / applyTranslation).
            double seedScale = 1.0 / Math.pow(2.0, ECC_LEVELS - 1);
            warp.put(0, 2, seedTx * seedScale);
            warp.put(1, 2, seedTy * seedScale);
            TermCriteria criteria = new TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 50, 1e-4);
            double lastCc = Double.NaN;
            boolean ok = false;
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

                    double maxPhase = Math.max(8.0, Math.min(refLvl.cols(), refLvl.rows()) * 0.12);
                    try {
                        Mat ref32 = new Mat();
                        Mat cur32 = new Mat();
                        refLvl.convertTo(ref32, CvType.CV_32F);
                        curLvl.convertTo(cur32, CvType.CV_32F);
                        Point shift = Imgproc.phaseCorrelate(ref32, cur32);
                        release(ref32, cur32);
                        double sx = clampDouble(shift.x, -maxPhase, maxPhase);
                        double sy = clampDouble(shift.y, -maxPhase, maxPhase);
                        double[] tx = warp.get(0, 2);
                        double[] ty = warp.get(1, 2);
                        warp.put(0, 2, tx[0] + sx);
                        warp.put(1, 2, ty[0] + sy);
                    } catch (Exception e) {
                        log.debug("positioning_diag stage=ecc_phase level={} skipped: {}", level, e.getMessage());
                    }

                    Mat warpBackup = warp.clone();
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
                        double cc = Video.findTransformECC(
                                refLvl,
                                curLvl,
                                warp,
                                Video.MOTION_TRANSLATION,
                                criteria,
                                levelMask
                        );
                        if (Double.isFinite(cc) && cc > 0.1) {
                            lastCc = cc;
                            ok = true;
                            log.debug(
                                    "positioning_diag stage=ecc_level level={} cc={} t=({}, {})",
                                    level,
                                    fmt(lastCc),
                                    fmt(warp.get(0, 2)[0]),
                                    fmt(warp.get(1, 2)[0])
                            );
                        } else {
                            warpBackup.copyTo(warp);
                            log.warn(
                                    "positioning_diag stage=ecc_level level={} REJECTED_low_cc cc={}",
                                    level,
                                    fmt(cc)
                            );
                        }
                    } catch (Exception e) {
                        warpBackup.copyTo(warp);
                        log.warn("positioning_diag stage=ecc_level level={} FAILED: {}", level, e.getMessage());
                    } finally {
                        release(warpBackup);
                        if (levelMask != null && levelMask != mask) {
                            levelMask.release();
                        }
                    }
                }

                double tx = warp.get(0, 2)[0];
                double ty = warp.get(1, 2)[0];
                double a = warp.get(0, 0)[0];
                double b = warp.get(1, 0)[0];
                double angleDeg = Math.toDegrees(Math.atan2(b, a));

                if (!ok || Math.hypot(tx, ty) > maxTranslationPx || Math.abs(angleDeg) > ECC_MAX_ANGLE_DEG) {
                    log.warn(
                            "positioning_diag stage=ecc abort apply ok={} t=({}, {}) angle={}",
                            ok,
                            fmt(tx),
                            fmt(ty),
                            fmt(angleDeg)
                    );
                    return new EccResult(aligned, false, lastCc, tx, ty, angleDeg);
                }

                Mat refined = new Mat();
                Imgproc.warpAffine(
                        aligned,
                        refined,
                        warp,
                        aligned.size(),
                        Imgproc.INTER_LINEAR | Imgproc.WARP_INVERSE_MAP,
                        Core.BORDER_REPLICATE
                );
                return new EccResult(refined, true, lastCc, tx, ty, angleDeg);
            } finally {
                release(warp);
                releaseAll(refPyr);
                releaseAll(curPyr);
            }
        } catch (Exception e) {
            log.warn("positioning_diag stage=ecc FAILED: {}", e.getMessage());
            return new EccResult(aligned, false, Double.NaN, 0, 0, 0);
        } finally {
            release(refGrayFull, curGrayFull, refRoi, curRoi, mask);
        }
    }

    private static boolean shouldSkipEcc(QualityScore q) {
        if (q == null) {
            return false;
        }
        double residual = Math.hypot(q.residualShiftX(), q.residualShiftY());
        return Double.isFinite(q.ncc()) && q.ncc() >= ECC_SKIP_NCC
                && Double.isFinite(q.meanAbsDiff()) && q.meanAbsDiff() <= ECC_SKIP_ABSDiff
                && residual <= ECC_SKIP_RESIDUAL_PX;
    }

    private static boolean isEccTransformPlausible(EccResult ecc, double maxTranslationPx) {
        return ecc != null
                && Math.hypot(ecc.tx(), ecc.ty()) <= maxTranslationPx
                && Math.abs(ecc.angleDeg()) <= ECC_MAX_ANGLE_DEG;
    }

    private static boolean isQualityImproved(QualityScore before, QualityScore after) {
        if (after == null || !Double.isFinite(after.meanAbsDiff()) || !Double.isFinite(after.ncc())) {
            return false;
        }
        if (before == null || !Double.isFinite(before.meanAbsDiff())) {
            return true;
        }
        double beforeRes = Math.hypot(before.residualShiftX(), before.residualShiftY());
        double afterRes = Math.hypot(after.residualShiftX(), after.residualShiftY());
        boolean absBetter = after.meanAbsDiff() + 0.25 < before.meanAbsDiff()
                || after.meanAbsDiff() <= before.meanAbsDiff() * 0.99;
        boolean nccBetter = Double.isFinite(before.ncc()) && after.ncc() >= before.ncc() + 0.008;
        boolean residualBetter = afterRes + 0.75 < beforeRes;
        boolean residualNotMuchWorse = afterRes <= beforeRes + 4.0;
        if ((absBetter || nccBetter) && residualNotMuchWorse) {
            return true;
        }
        return residualBetter && after.meanAbsDiff() <= before.meanAbsDiff() + 0.75;
    }

    /** Accept ORB when absdiff improves a lot even if residual stays large (ECC/polish will finish). */
    private static boolean isOrbResidualAcceptable(QualityScore before, QualityScore after) {
        if (before == null || after == null) {
            return false;
        }
        if (!Double.isFinite(after.meanAbsDiff()) || !Double.isFinite(before.meanAbsDiff())) {
            return false;
        }
        return after.meanAbsDiff() <= before.meanAbsDiff() * 0.72
                && after.meanAbsDiff() + 4.0 < before.meanAbsDiff();
    }

    private ResidualPolish polishResidualTranslation(
            Mat reference,
            Mat working,
            Mat homographyCurToRef,
            Rect qualityRoi,
            List<NormPoint> polygon
    ) {
        QualityScore before = measureQuality(reference, working, qualityRoi, polygon);
        double dx = before.residualShiftX();
        double dy = before.residualShiftY();
        double mag = Math.hypot(dx, dy);
        if (!Double.isFinite(mag) || mag < RESIDUAL_POLISH_MIN_PX || mag > RESIDUAL_POLISH_MAX_PX) {
            return ResidualPolish.none();
        }

        // Try both signs — phaseCorrelate vs warpAffine convention can disagree per OpenCV build/ROI.
        ResidualCandidate best = null;
        double[] signs = {1.0, -1.0};
        for (double sign : signs) {
            double sx = dx * sign;
            double sy = dy * sign;
            Mat candidate = applyTranslation(working, sx, sy);
            QualityScore after = measureQuality(reference, candidate, qualityRoi, polygon);
            double afterRes = Math.hypot(after.residualShiftX(), after.residualShiftY());
            boolean residualCollapsed = afterRes <= mag * 0.55 || afterRes + 2.0 < mag;
            boolean absOk = Double.isFinite(after.meanAbsDiff())
                    && after.meanAbsDiff() <= before.meanAbsDiff() + 1.0;
            boolean qualityOk = isQualityImproved(before, after)
                    || isOrbResidualAcceptable(before, after)
                    || (residualCollapsed && absOk);
            if (!qualityOk) {
                candidate.release();
                continue;
            }
            double score = -after.meanAbsDiff() * 2.0 - afterRes;
            if (best == null || score > best.score) {
                if (best != null) {
                    best.frame.release();
                }
                best = new ResidualCandidate(candidate, after, sx, sy, score);
            } else {
                candidate.release();
            }
        }
        if (best == null) {
            return ResidualPolish.none();
        }

        Mat t = translationHomography(best.dx, best.dy);
        Mat empty = new Mat();
        Mat composed = new Mat();
        try {
            if (homographyCurToRef != null && !homographyCurToRef.empty()) {
                Core.gemm(t, homographyCurToRef, 1.0, empty, 0.0, composed);
            } else {
                t.copyTo(composed);
            }
            return new ResidualPolish(true, best.frame, composed.clone(), best.quality, best.dx, best.dy);
        } finally {
            release(t, empty, composed);
        }
    }

    private static final class ResidualCandidate {
        final Mat frame;
        final QualityScore quality;
        final double dx;
        final double dy;
        final double score;

        ResidualCandidate(Mat frame, QualityScore quality, double dx, double dy, double score) {
            this.frame = frame;
            this.quality = quality;
            this.dx = dx;
            this.dy = dy;
            this.score = score;
        }
    }

    private static boolean isEccQualityBetter(QualityScore before, QualityScore after) {
        if (before == null || after == null) {
            return false;
        }
        if (!Double.isFinite(after.meanAbsDiff()) || !Double.isFinite(after.ncc())) {
            return false;
        }
        boolean absBetter = !Double.isFinite(before.meanAbsDiff())
                || after.meanAbsDiff() + 0.5 < before.meanAbsDiff()
                || after.meanAbsDiff() <= before.meanAbsDiff() * 0.97;
        boolean nccBetter = !Double.isFinite(before.ncc())
                || after.ncc() >= before.ncc() - 0.03;
        if (!absBetter || !nccBetter) {
            return false;
        }
        // Strong absdiff wins even if residual phaseCorrelate drifts (frame 20 cam=2 case).
        double absDrop = before.meanAbsDiff() - after.meanAbsDiff();
        if (absDrop >= 1.5 || after.meanAbsDiff() <= before.meanAbsDiff() * 0.75) {
            return true;
        }
        double beforeRes = Math.hypot(before.residualShiftX(), before.residualShiftY());
        double afterRes = Math.hypot(after.residualShiftX(), after.residualShiftY());
        return afterRes <= beforeRes + 3.0;
    }

    private static double clampDouble(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
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

    private PreparedReference getOrBuildPreparedReference(
            Mat reference,
            String referenceCacheKey,
            Mat interestMaskFull
    ) {
        PreparedReference cached = preparedReferenceCache;
        if (cached != null && matchesPreparedReference(cached, referenceCacheKey, reference)) {
            return cached;
        }
        PreparedReference next = buildPreparedReference(reference, referenceCacheKey, interestMaskFull);
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

    private PreparedReference buildPreparedReference(
            Mat reference,
            String referenceCacheKey,
            Mat interestMaskFull
    ) {
        Mat gray = new Mat();
        Mat scaled = null;
        Mat maskScaled = null;
        Mat descriptors = new Mat();
        MatOfKeyPoint keypoints = new MatOfKeyPoint();
        try {
            Imgproc.cvtColor(reference, gray, Imgproc.COLOR_BGR2GRAY);
            clahe.apply(gray, gray);
            ResizeResult resized = resizeForProcessing(gray, MAX_ORB_DIM);
            scaled = resized.mat;
            Mat detectMask = new Mat();
            if (interestMaskFull != null && !interestMaskFull.empty()) {
                maskScaled = new Mat();
                Imgproc.resize(
                        interestMaskFull,
                        maskScaled,
                        scaled.size(),
                        0,
                        0,
                        Imgproc.INTER_NEAREST
                );
                detectMask = maskScaled;
            }
            orb.detectAndCompute(scaled, detectMask, keypoints, descriptors);
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
            release(gray, scaled, maskScaled, descriptors);
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

    private QualityScore measureQuality(Mat reference, Mat current, Rect roi, List<NormPoint> polygon) {
        Mat refGray = new Mat();
        Mat curGray = new Mat();
        Mat absDiff = new Mat();
        Mat result = new Mat();
        Mat ref32 = new Mat();
        Mat cur32 = new Mat();
        Mat mask = null;
        try {
            Imgproc.cvtColor(reference, refGray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.cvtColor(current, curGray, Imgproc.COLOR_BGR2GRAY);
            Rect safe = toSafeRect(
                    new RoiRect(roi.x, roi.y, roi.width, roi.height),
                    reference.cols(),
                    reference.rows()
            );
            Mat refRoi = new Mat(refGray, safe);
            Mat curRoi = new Mat(curGray, safe);
            if (polygon != null && polygon.size() >= 3) {
                mask = RoiPolygonMask.maskForRect(polygon, safe, reference.cols(), reference.rows());
            }

            Core.absdiff(refRoi, curRoi, absDiff);
            Scalar meanDiff = mask == null || mask.empty()
                    ? Core.mean(absDiff)
                    : Core.mean(absDiff, mask);
            double meanAbs = meanDiff.val[0];

            double ncc = Double.NaN;
            try {
                Imgproc.matchTemplate(refRoi, curRoi, result, Imgproc.TM_CCOEFF_NORMED);
                if (!result.empty()) {
                    ncc = result.get(0, 0)[0];
                }
            } catch (Exception ignored) {
                // leave NaN
            }

            double dx = 0;
            double dy = 0;
            try {
                refRoi.convertTo(ref32, CvType.CV_32F);
                curRoi.convertTo(cur32, CvType.CV_32F);
                Point shift = Imgproc.phaseCorrelate(ref32, cur32);
                dx = shift.x;
                dy = shift.y;
            } catch (Exception ignored) {
                // leave 0
            }

            return new QualityScore(meanAbs, ncc, dx, dy);
        } catch (Exception e) {
            return new QualityScore(Double.NaN, Double.NaN, 0, 0);
        } finally {
            release(refGray, curGray, absDiff, result, ref32, cur32, mask);
        }
    }

    private static void putQuality(Map<String, Object> diag, String stage, QualityScore q) {
        putFinite(diag, stage + "_mean_absdiff", q.meanAbsDiff());
        putFinite(diag, stage + "_ncc", q.ncc());
        putFinite(diag, stage + "_residual_dx", q.residualShiftX());
        putFinite(diag, stage + "_residual_dy", q.residualShiftY());
    }

    private static void putFinite(Map<String, Object> diag, String key, double v) {
        if (Double.isFinite(v)) {
            diag.put(key, v);
        }
    }

    /** Map.copyOf rejects null values — never put nulls into diagnostics. */
    private static Map<String, Object> stripNulls(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>(in.size());
        for (Map.Entry<String, Object> e : in.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private static String ctx(Map<String, Object> logContext) {
        if (logContext == null || logContext.isEmpty()) {
            return "";
        }
        Object cam = logContext.get("camera_id");
        Object frame = logContext.get("frame_id");
        StringBuilder sb = new StringBuilder();
        if (cam != null) {
            sb.append("cam=").append(cam);
        }
        if (frame != null) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append("frame=").append(frame);
        }
        return sb.toString();
    }

    private static String fmt(double v) {
        if (!Double.isFinite(v)) {
            return "nan";
        }
        return String.format(Locale.US, "%.3f", v);
    }

    private record QualityScore(
            double meanAbsDiff,
            double ncc,
            double residualShiftX,
            double residualShiftY
    ) {
    }

    private record OrbResult(
            Mat homography,
            int refKeypoints,
            int curKeypoints,
            int goodMatches,
            int inliers
    ) {
    }

    private record EccResult(
            Mat refined,
            boolean ok,
            double correlation,
            double tx,
            double ty,
            double angleDeg
    ) {
    }

    private record ResidualPolish(
            boolean applied,
            Mat frame,
            Mat homography,
            QualityScore quality,
            double dx,
            double dy
    ) {
        static ResidualPolish none() {
            return new ResidualPolish(false, null, null, null, 0, 0);
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
