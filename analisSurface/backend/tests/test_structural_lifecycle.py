"""Compact raw structure must survive the complete inspection/learning lifecycle."""
import cv2
import numpy as np
import pytest

from app.services.analysis_settings import AnalysisSettings
from app.services.inspection_service import InspectionService
from app.services.inspection_evidence import pre_normalization_compact_signal, compact_residual_evidence
from app.services.learned_normals import reference_fingerprint

SETTINGS = AnalysisSettings(use_patchcore=False, enable_clahe=False, min_diff_signal=10.57088, min_defect_area=5)
H = [1.,0.,0.,0.,1.,0.,0.,0.,1.]

@pytest.mark.parametrize("inside_fp", [False, True])
def test_pale_patch_survives_inspection_review_learning_and_new_defect(inspection_service, inside_fp):
    s = inspection_service
    product = "raw-pale-patch"
    s.update_analysis_settings(product, SETTINGS.to_dict())
    reference = np.full((180,260,3),180,np.uint8)
    first = reference.copy()
    first[65:85,55:79] = 164  # Smooth interior used to be removed as illumination.
    s.set_reference_frame(product,reference)
    if inside_fp:
        s.add_fp_zone(product,[(0.,0.),(1.,0.),(1.,1.),(0.,1.)],260,180,
                      aligned=reference,reference_hash=reference_fingerprint(reference))
    kwargs = dict(settings=SETTINGS,threshold=.25,alignment_h_ref_to_cur=H,include_visuals=True)
    result = s.inspect_frame(product,first,**kwargs)
    assert result.anomaly_score > .25
    assert np.any(result.segmentation_mask[65:85,55:79])
    review = s.get_learning_review(result.inspection_id)
    assert review["defects"]
    s.accept_all_review_defects_as_normal(result.inspection_id)
    assert s.inspect_frame(product,first,**kwargs).anomaly_score < .25
    changed = first.copy()
    changed[65:85,185:209] = 164
    result = s.inspect_frame(product,changed,**kwargs)
    assert result.anomaly_score > .25
    assert np.any(result.segmentation_mask[65:85,185:209])
    # A reference replacement cannot inherit previous region decisions.
    s.set_reference_frame(product, np.full_like(reference,185))
    assert s.inspect_frame(product,changed,**kwargs).anomaly_score > .25

@pytest.mark.parametrize("kind", ["smooth_light", "uniform", "jitter", "thin_rim"])
def test_raw_branch_does_not_promote_light_or_registration_noise(kind):
    reference = np.full((180,260),180,np.uint8)
    current = reference.copy()
    if kind == "smooth_light":
        yy,xx=np.mgrid[:180,:260]
        current=np.clip(reference.astype(float)+40*np.exp(-((xx-130)**2/24**2+(yy-90)**2/12**2)),0,255).astype(np.uint8)
    elif kind == "uniform": current[:]=200
    elif kind == "jitter":
        cv2.putText(reference,"PRINT",(25,100),cv2.FONT_HERSHEY_SIMPLEX,1.3,30,2)
        current=cv2.warpAffine(reference,np.float32([[1,0,2],[0,1,0]]),(260,180),borderMode=cv2.BORDER_REPLICATE)
    else:
        cv2.ellipse(reference,(120,90),(80,35),0,180,360,40,3)
        current=cv2.warpAffine(reference,np.float32([[1,0,1],[0,1,1]]),(260,180),borderMode=cv2.BORDER_REPLICATE)
    assert not np.any(pre_normalization_compact_signal(reference,current,SETTINGS))

def test_saved_boundary_pale_defect_is_separated_and_neighbouring_rim_decisions_are_preserved():
    from pathlib import Path
    fixture = Path(__file__).resolve().parents[1]/"test_data/compact_residuals/structural.npz"
    with np.load(fixture,allow_pickle=False) as f:
        for name in ("rim_cam6_234","rim_cam6_236","rim_cam6_240","rim_cam6_243"):
            gray=f[name]
            assert compact_residual_evidence(gray,cv2.GaussianBlur(gray,(3,3),0),SETTINGS)[0] < .25,name
        gray=f["defect_cam6_231"]
        assert compact_residual_evidence(gray,cv2.GaussianBlur(gray,(3,3),0),SETTINGS)[0] > .25
        ref,cur=f["reference_cam6_229"],f["current_cam6_229"]
        signal=pre_normalization_compact_signal(ref,cur,SETTINGS)
        assert np.any(signal)
        s=InspectionService.__new__(InspectionService);s._anomaly_engine=None
        diff=s._compute_advanced_difference(cv2.cvtColor(cur,cv2.COLOR_GRAY2BGR),cv2.cvtColor(ref,cv2.COLOR_GRAY2BGR),
                                            SETTINGS,illumination_compensation=True)
        assert s._run_anomaly_model(diff,SETTINGS)[0] > .25

@pytest.mark.parametrize("deferred", [False, True])
def test_review_keeps_its_geometry_after_save_reload_and_reference_change(inspection_service, deferred):
    from app.services.learned_normals import InspectionReviewStore
    s=inspection_service
    product="geometry-snapshot"
    reference=np.full((180,260,3),180,np.uint8)
    current=reference.copy();current[60:80,60:84]=164
    roi=[(.05,.05),(.95,.05),(.95,.95),(.05,.95)]
    line=[(.5,.9),(.5,.1)]
    s.set_reference_frame(product,reference);s.set_roi_polygon(product,roi);s.set_perspective_line(product,line)
    result=s.inspect_frame(product,current,settings=SETTINGS,threshold=.25,alignment_h_ref_to_cur=H,
                           defer_learning_review=deferred)
    if deferred:s._deferred_learning_reviews._queue.join()
    # Read through a new store instance to include disk serialization.
    store=InspectionReviewStore(storage_dir=s._learning_reviews.storage_dir,session_wipe=False)
    review=store.get(result.inspection_id)
    assert review is not None
    assert review.geometry_context["roi_polygon"] == [list(p) for p in roi]
    assert review.geometry_context["perspective_line"] == [list(p) for p in line]
    s.set_roi_polygon(product,[(.6,.6),(.9,.6),(.9,.9),(.6,.9)])
    s.set_perspective_line(product,[(.1,.5),(.9,.5)])
    score_before=s._review_counterfactual(review)[0]
    s.set_reference_frame(product,np.full_like(reference,175))
    # Replacement must not use the new reference/polygon on an old review.
    assert s._review_counterfactual(review)[0] == pytest.approx(score_before)
