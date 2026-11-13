import { useEffect, useState, useSyncExternalStore } from "react";
import { orchestratorWs } from "../../shared/ws";
import { InspectionStatistics } from "./inspectionStatistics";

export function useInspectionStatistics(cameraIds: number[], resetVersion: number) {
  const [statistics] = useState(() => new InspectionStatistics(cameraIds));
  const counts = useSyncExternalStore(statistics.subscribe, statistics.getSnapshot, statistics.getSnapshot);
  useEffect(() => { statistics.configure(cameraIds); }, [statistics, cameraIds]);
  useEffect(() => {
    if (resetVersion > 0) statistics.reset();
  }, [statistics, resetVersion]);
  useEffect(() => {
    const unsubscribe = orchestratorWs.onMessage(message => {
      if (message.type === "server.inspect_result") statistics.recordInspection(message.payload);
      if (message.type === "server.inspect_bucket_result") statistics.recordBucket(message.payload);
      if (message.type === "server.preview_frame") statistics.observeFrame(message.payload);
      if (message.type === "server.preview_batch") message.payload.frames.forEach(frame => statistics.observeFrame(frame));
    });
    return () => { unsubscribe(); };
  }, [statistics]);
  return counts;
}
