# positioning — architecture "WHY"

Back to [overview](./overview.md) · Evidence tags as elsewhere.

## Timeline (from git)
| Date | Commit | Event |
|---|---|---|
| 2026‑07‑14 | `83bb9d19` “позиционирование” | module created (README, pom, `BucketPositioningService` 429 lines, SHM reader/writer, IMLB loop); config blocks added in `01-core`/`20-detectors` |
| 2026‑07‑14 | `f9c260dc`, `92f2824a` (“позиционирование фикс4 баг reject”), `81ff1ac9` | tuning and a reject-handling bug |
| 2026‑09‑22 | `3df6b127` “урезание позиционирования” | stages trimmed (the four `ENABLE_*` flags are false today) – speed |
| 2026‑09‑30 | `9ba4efc5` “Optimize inspection pipeline and centralize positioning” | positioning centralised in Python; `BinaryInspectHeaders` simplified (‑31 lines), `InspectPythonExecutor` passes `inspect_scale` |
| 2026‑10‑15 | `c11d7b13` “cut position preparing etalon once” | Python `FramePositioner` rewritten (351 lines changed) with cached reference preparation |
| 2026‑10‑15 | `a8f65721`, `INSPECTION_OPTIMIZATION.md` | latency optimisation; Java stage remains disabled |

## Why it existed
⚠️ [GUESS/CONJECTURE] Buckets arrive with varying pose (“большая разбежка положений между кадрами — ожидаема” — README); both geometry and the difference-map detector need registered input. A dedicated process with OpenCV Java was the quickest way to reuse the ORB code already written for geometry.

## Why it was replaced by Python phase correlation
- **[doc]** `20-detectors.yaml` comment: positioning runs inside Python (`PY_POSITIONING=1`); **[doc]** `INSPECTION_OPTIMIZATION.md` 10‑15: reference preparation computed once, immutable caches.
- Removes one IPC hop + one SHM write/read (a 3.7 MB aligned frame per camera) per cycle, and aligns at *full resolution* before `inspect_scale` downscale (“Python aligns first and then resizes the pair atomically, avoiding the historic full-reference/downscaled-frame mismatch” — comment `InspectPythonExecutor:187-191`).
- Phase correlation of a high-passed ROI (σ=3) is much cheaper than ORB+RANSAC and gives sub‑pixel translation, which is what the line really needs (the bucket moves on a conveyor; rotation is small). The Python positioner explicitly refuses large shifts (`max_shift_px 32`) and verifies improvement (`error_after < 0.99·error_before`, clipped loss) before applying.
- ⚠️ [GUESS/CONJECTURE] Real-time budget (3.5 s for 10 cameras) forced removal of ECC/polish/coarse stages first (`3df6b127`), then the whole Java hop.

## Behavioural lessons preserved in code
- On failed alignment the **raw** frame continues (not marked aligned) so Python never receives an identity pose lock on a corrupt buffer: comment “иначе в SHM может лежать raw/битый кадр, а Python получит identity H и даст anomaly=1.0” (`InspectPositioningExecutor:~228`).
- `fail_on_reject=false` + `max_shift_mm=9999`: shifts are never a reject reason at positioning time; the QC of pose is left to geometry (`applyPoseQcFromPositioning`).
