# Current latency requirement — 500 ms from trigger to verdict

Back to [project context](../PROJECT_CONTEXT.md) · [historical measurements](./2026-10-16-log-review.md) · [optimisation plan](../analis-surface/optimization_plan.md) · [six-core RT context](../deploy-ops/production_environment.md)

**[requirement: owner, 2026-10-20] The pipeline must produce the final ГОДЕН / БРАК verdict within 500 ms of the relevant trigger.** This supersedes 600 ms as the current target. Recording the requirement does not change timeouts or demonstrate compliance; the historical 2026-10-16 measurements retain their original thresholds.

## 1. Measurement boundaries

- Start: the agreed trigger event for the inspected item. Log the physical signal identity and the corresponding received-software event separately. DI2 window opening, DI3 edge and the camera's electrical trigger must not be substituted for each other without checking the wiring/mode.
- End: the final group verdict after all required enabled cameras/checks are accounted for. For simultaneous completion of both groups, use the later verdict. A passing camera alone is insufficient; early reject is useful but does not establish the latency of passing items.
- Queues, frame wait/transfer, preprocessing, positioning, enabled geometry, surface analysis and aggregation belong in the interval. UI rendering is optional work after the decision.
- Keep verdict-to-DO-write and observed DI2 falling as additional measurements. The earlier operational need includes timely reject output; a 500 ms verdict must not be presented as proof that physical output or mechanical actuation occurred by that time.

Correlate by process/session and inspection sequence; resolve camera `frame_id` using inspection result events rather than assuming equal IDs. Distinguish software timestamps from physical edge measurements. Existing DI scan timing can add uncertainty near the deadline. [Log correlation rules](./2026-10-16-log-review.md#2-correlation-rules).

## 2. What the current evidence supports

**[logs: 2026-10-16]** Last-frame-to-verdict time averaged 258 ms (maximum 387) in session A and 439 ms (maximum 1332) in B. Last-frame-to-output time is a different interval. The approximately 2277 ms frame-request wait includes waiting for external triggering, whose exact physical edge is not separately logged. None of these numbers proves a 500 ms trigger-to-verdict deadline.

**[code]** Existing multi-second cycle/bucket timeouts are failure-handling settings, not the requested 500 ms budget. Reducing a timeout to 500 ms can produce faster technical rejections without completing the requested inspection; timeout handling needs an agreed policy and separate counts.

## 3. Proposed work order

Implementation update, 2026-10-20: bulk normal/FP preparation, pinned state and parts of comparison/allocation reduction are now implemented; see [local validation](./2026-10-20-learning-optimization.md). The six-core concurrency configuration and production deadline have not been validated by that local change.

1. Resolve the trigger/frame timeline and separately time acquisition, queues, geometry, positioning, diff, learned-state refresh/matching, FP checks, aggregation and output. Keep telemetry bounded.
2. Prepare consistent learned-normal/FP updates outside the critical path; apply a complete version at an agreed cycle boundary. Existing logs show the compound learned-normal/FP interval reaching 1020 ms, not the isolated reload cost.
3. Reduce repeated comparisons and image conversions, then benchmark process/thread combinations against the actual CPU allocation of the six-core RT host. Existing reference caches and deferred artifacts should be retained.
4. Include the required geometry work in the same budget. Where dependencies permit, evaluate overlapping geometry and surface computation, measuring CPU competition and verdict equivalence; no benefit is assumed in advance.
5. Consider a selective native kernel only for a measured remaining bottleneck. Preserve small-defect detection, positioning behavior and learned-normal/FP safeguards.

**[proposal]** Aim below the limit, for example 350–400 ms for the complete trigger-to-verdict interval, leaving headroom. This is an engineering margin proposal, not a measured result or customer-approved allocation of time to each stage.

## 4. Acceptance evidence still required

Use long production-host runs with every enabled camera, cold/warm caches, normal-store growth, live learning updates and competing UI/history work. Include both pass and reject cases and the required geometry path. Record sample count, P50/P95/P99/max, missing/partial cycles, deadline misses and technical failures with the exact start/end events.

Averages or tests of one camera cannot establish a hard per-item limit. Record observed misses rather than hiding them behind early rejects or omitted failures. Establish quality equivalence using fixed images/settings/references and labelled cases; logs alone cannot prove false-reject/defect-recall performance. Hardware timing must be checked separately if the deadline is defined at electrical edges.
