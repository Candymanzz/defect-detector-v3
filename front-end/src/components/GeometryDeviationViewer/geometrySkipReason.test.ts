import { expect, it } from "vitest";
import { geometrySkipReason } from "./geometrySkipReason";

it("never interprets compatibility PASS flags on SKIPPED as a measurement", () => {
  expect(geometrySkipReason({status:"SKIPPED", overallPass:true, jointPass:true,
    error:"geometry skipped: no joint ROI from client"})).toContain("не задана область шва");
  expect(geometrySkipReason({status:"PASS",overallPass:true})).toBeNull();
});
