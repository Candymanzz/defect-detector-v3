import { beforeEach, expect, it, vi } from "vitest";
import { createOrientedRectFromAxis } from "../components/RoiContourEditor/orientedRectRoi";
import { createReferenceBundleFromCameraFrames } from "../components/ReferenceSetup/referenceBundle";
import type { PreviewFramePayload } from "./ws";
import { clearReferenceImages, commitReferenceBundleImages, getReferenceImage, stageReferenceBundleImages,
  resolveReferenceBundleImages, getArchivedReferenceGroups } from "./referenceImages";

vi.mock("./api", () => ({ HttpError: class extends Error {}, orchestratorApi: { getLearnedNormalCases: vi.fn(async()=>[]) } }));
const roi=[{x:.1,y:.1},{x:.9,y:.1},{x:.9,y:.9},{x:.1,y:.9}];
function frame(cam:number): PreviewFramePayload {
  return {camera_id:cam,frame_id:String(cam),session_state:"READY",server_ts_ms:1,detector:{product_type:"bench"},
    current:{camera_id:cam,frame_id:String(cam),shm_name:"/test",width:100,height:80,stride:300,shm_offset:0,pixel_format:"bgr_u8",channels:3}};
}
beforeEach(()=>clearReferenceImages());
it("preserves rotated seam polygons on ACK, archive and restore without staged editor contours",()=>{
  const seam=createOrientedRectFromAxis({x:.2,y:.3},{x:.8,y:.7},.04);
  const bundle=createReferenceBundleFromCameraFrames([5,9],9,{5:frame(5),9:frame(9)},{5:roi,9:roi},seam,[]);
  stageReferenceBundleImages("reject",bundle,{5:"test5",9:"test9"});
  resolveReferenceBundleImages("reject",false);
  expect(getReferenceImage(9)).toBeUndefined();
  stageReferenceBundleImages("ack",bundle,{5:"test5",9:"test9"});
  resolveReferenceBundleImages("ack",true);
  expect(getReferenceImage(9)?.jointRoiPoints).toEqual(seam);
  const withoutSeam=createReferenceBundleFromCameraFrames([5,9],5,{5:frame(5),9:frame(9)},{5:roi,9:roi},[],[]);
  commitReferenceBundleImages(withoutSeam,{5:"new5",9:"new9"});
  expect(getReferenceImage(9)?.jointRoiPoints).toBeUndefined();
  const archive=getArchivedReferenceGroups().find(a=>a.jointCameraId===9);
  expect(archive).toBeDefined();
  commitReferenceBundleImages(archive!.bundle,archive!.imageUrlsByCameraId);
  expect(getReferenceImage(9)?.jointRoiPoints).toEqual(seam);
});
