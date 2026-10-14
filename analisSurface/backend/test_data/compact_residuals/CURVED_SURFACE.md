# Curved surface geometry

`curved_surface.npz` adds a frozen geometry model/map to the existing crop in
`far_tip.npz` (see `FAR_TIP.md`). Both use the saved camera 5 full reference and
ROI from the 9 October 2026 run at 13:16. The model is fitted at 1224 x 1024;
the working frame is 918 x 768; the crop origin is (350, 30).

The drawn arc is usable, but reference gradients do not corroborate it within
the narrow search band. This fixture therefore exercises the conservative
ROI-only model, with confidence 0.5736 and maximum stretch 1.5736.

Spots inserted into the reference crop are **synthetic controls**, not labelled
defects in inspection 196. At the existing fixture's far-end point, black spots
of radius 3 and 5 pixels score 30.29 -> 35.41% and 41.20 -> 46.57%. A two-pixel
spot changes only 27.08 -> 27.16%; geometry does not recover absent samples.
The unmodified reference stays at zero difference. The test checks both the
map's cropped coordinate system and its effect before/after light removal.
