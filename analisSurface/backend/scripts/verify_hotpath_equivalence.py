"""Compare a saved pre-change implementation with optimized CV on frozen frames.

The timing covers warm difference-map computation, not capture, Java or PLC I/O.
Use --baseline-dir containing the original inspection_service/evidence.py files.
"""
import argparse
from dataclasses import fields
import json
from pathlib import Path
import statistics
import sys
import time
import types

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
import cv2
import numpy as np
from app.services.analysis_settings import AnalysisSettings
from app.services import inspection_evidence
from app.services.inspection_service import InspectionService
from app.services.inspection_geometry import polygon_bbox_from_norm_points, polygon_mask_from_norm_points, perspective_roi_far_weights
from app.services.inspection_curvature import estimate_curvature, with_curvature, crop_weights
from app.services.inspection_positioning import FramePositioner


def historical(directory, name):
    path = directory / (name + '.py')
    module = types.ModuleType('baseline_' + name)
    module.__file__ = str(path)
    sys.modules[module.__name__] = module
    exec(compile(path.read_text(encoding='utf-8-sig'), str(path), 'exec'), module.__dict__)
    return module


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline-dir', type=Path, required=True)
    parser.add_argument('--dataset', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--repeats', type=int, default=6)
    args = parser.parse_args()
    cv2.setNumThreads(1); cv2.ocl.setUseOpenCL(False)
    evidence = historical(args.baseline_dir, 'inspection_evidence')
    baseline = historical(args.baseline_dir, 'inspection_service')
    baseline.compact_residual_evidence = evidence.compact_residual_evidence
    baseline.pre_normalization_compact_signal = evidence.pre_normalization_compact_signal
    old = baseline.InspectionService.__new__(baseline.InspectionService)
    new = InspectionService.__new__(InspectionService)
    old._anomaly_engine = new._anomaly_engine = None
    report = {'difference_checks': [], 'evidence_checks': [], 'benchmark': []}
    positioner = FramePositioner()
    for meta_path in sorted(args.dataset.glob('camera-*.json')):
        meta = json.loads(meta_path.read_text())
        w, h = meta['width'], meta['height']
        reference = np.fromfile(meta_path.with_suffix('.ref.bin'),np.uint8).reshape(h,w,3)
        current = np.fromfile(meta_path.with_suffix('.current.bin'),np.uint8).reshape(h,w,3)
        current = positioner.position(current,reference,product_type=meta_path.stem).aligned
        size=(round(w*.75),round(h*.75))
        reference=cv2.resize(reference,size,interpolation=cv2.INTER_LINEAR)
        current=cv2.resize(current,size,interpolation=cv2.INTER_LINEAR)
        settings=AnalysisSettings.from_overrides({k:v for k,v in meta.get('settings',{}).items()
                                                 if k in {f.name for f in fields(AnalysisSettings)}})
        polygon=meta.get('polygon'); line=meta.get('line')
        weights=None
        if polygon and line:
            weights=with_curvature(perspective_roi_far_weights(*size,tuple(map(tuple,line)),tuple(map(tuple,polygon)),
                                  (0,0,*size),.35),estimate_curvature(reference,polygon,line),*size,polygon)
        bbox=polygon_bbox_from_norm_points(*size,polygon,padding=48) if polygon else (0,0,*size)
        x,y,cw,ch=bbox; ref_crop=reference[y:y+ch,x:x+cw]
        kw=dict(settings=settings,vertical_compensation=True,
                perspective_weights=crop_weights(weights,bbox) if weights is not None else None,
                vertical_compensation_frame=(size[1],y),
                roi_vertical_bounds=(min(round(p[1]*(size[1]-1)) for p in polygon),
                                     max(round(p[1]*(size[1]-1)) for p in polygon)+1) if polygon else None)
        def difference(service,image):
            return service._compute_advanced_difference(image[y:y+ch,x:x+cw],ref_crop,**kw)
        for variant in ('original','glare','scratch'):
            image=current.copy()
            if variant=='glare': image=np.clip(image.astype(np.int16)+22,0,255).astype(np.uint8)
            if variant=='scratch': cv2.line(image,(x+cw//2,y+ch//3),(x+cw//2+4,y+ch//3+35),(15,15,15),2)
            before=difference(old,image); after=difference(new,image)
            full_before=np.zeros_like(reference);full_after=np.zeros_like(reference)
            full_before[y:y+ch,x:x+cw]=before;full_after[y:y+ch,x:x+cw]=after
            if polygon:
                outside=polygon_mask_from_norm_points(*size,polygon)==0
                full_before[outside]=0;full_after[outside]=0
            score_before,mask_before=old._run_anomaly_model(full_before,settings,perspective_weights=weights)
            score_after,mask_after=new._run_anomaly_model(full_after,settings,perspective_weights=weights)
            ok=np.array_equal(before,after) and np.array_equal(mask_before,mask_after) and score_before==score_after
            row=dict(camera=meta_path.stem,variant=variant,identical=bool(ok),
                     max_pixel_difference=int(cv2.absdiff(before,after).max()),old_score=score_before,new_score=score_after)
            report['difference_checks'].append(row)
            if not ok: raise AssertionError(row)
        times={'before':[],'after':[]}
        for i in range(args.repeats):
            for label,service in ([('before',old),('after',new)] if i%2==0 else [('after',new),('before',old)]):
                started=time.perf_counter();difference(service,current)
                times[label].append((time.perf_counter()-started)*1000)
        report['benchmark'].append(dict(camera=meta_path.stem,**{label:statistics.median(v) for label,v in times.items()}))
    for path in sorted((BACKEND/'test_data/compact_residuals').glob('*.npz')):
        with np.load(path,allow_pickle=False) as arrays:
            for name in arrays.files:
                image=arrays[name]
                if image.dtype!=np.uint8 or image.ndim!=2: continue
                blur=cv2.GaussianBlur(image,(3,3),0)
                for separation in (False,True):
                    for floor in (8.,12.,18.):
                        settings=AnalysisSettings(use_patchcore=False,min_diff_signal=floor)
                        a=evidence.compact_residual_evidence(image,blur,settings,allow_boundary_separation=separation)
                        b=inspection_evidence.compact_residual_evidence(image,blur,settings,allow_boundary_separation=separation)
                        ok=a[0]==b[0] and np.array_equal(a[1],b[1])
                        report['evidence_checks'].append(dict(source=path.name,case=name,separation=separation,floor=floor,identical=bool(ok)))
                        if not ok: raise AssertionError(report['evidence_checks'][-1])
    if not report['benchmark']: raise RuntimeError('No frozen camera frames found')
    before=sum(r['before'] for r in report['benchmark']);after=sum(r['after'] for r in report['benchmark'])
    report['summary']=dict(difference_checks=len(report['difference_checks']),evidence_checks=len(report['evidence_checks']),
                           before_sum_median_ms=before,after_sum_median_ms=after,improvement_percent=100*(before-after)/before)
    args.output.write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps(report['summary']),flush=True)


if __name__=='__main__': main()
