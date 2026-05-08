import { useCallback, useState } from "react";
import { BATCH_REQUEST_TIMEOUT_MS } from "../../app/constants";

export function useBatchTests({ apiFetch, productType, threshold, runTracked }) {
  const [normalTestLogs, setNormalTestLogs] = useState([]);
  const [normalTestSummary, setNormalTestSummary] = useState(null);
  const [brackTestLogs, setBrackTestLogs] = useState([]);
  const [brackTestSummary, setBrackTestSummary] = useState(null);

  const runBatchTest = useCallback(
    async (dataset) => {
      await runTracked({
        event: "test_run",
        payload: {
          product_type: productType,
          threshold,
          dataset
        },
        run: async () => {
          const formData = new FormData();
          formData.append("product_type", productType);
          formData.append("threshold", String(threshold));
          formData.append("dataset", dataset);

          const res = await apiFetch(
            "/test-run",
            { method: "POST", body: formData },
            { event: "test_run", timeoutMs: BATCH_REQUEST_TIMEOUT_MS }
          );
          if (!res.ok) throw new Error("Ошибка тестирования");

          const data = await res.json();
          if (dataset === "normal") {
            setNormalTestLogs(data.logs || []);
            setNormalTestSummary(data.summary || null);
          } else {
            setBrackTestLogs(data.logs || []);
            setBrackTestSummary(data.summary || null);
          }
          return data;
        },
        buildFinishedPayload: (data) => ({
          threshold: Number(data.threshold || threshold),
          total: Number(data.summary?.total || 0),
          defect_count: Number(data.summary?.defect_count || 0)
        })
      });
    },
    [apiFetch, productType, runTracked, threshold]
  );

  const resetBatchTests = useCallback(() => {
    setNormalTestLogs([]);
    setNormalTestSummary(null);
    setBrackTestLogs([]);
    setBrackTestSummary(null);
  }, []);

  return {
    normalTestLogs,
    normalTestSummary,
    brackTestLogs,
    brackTestSummary,
    runBatchTest,
    resetBatchTests
  };
}
