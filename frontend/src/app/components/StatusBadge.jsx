import { AlertTriangle, CheckCircle2 } from "lucide-react";

const getStatusClass = (status) => {
  if (status === "ГОДЕН") return "bg-ok/20 text-ok border-ok/40";
  if (status === "БРАК") return "bg-ng/20 text-ng border-ng/40";
  return "bg-slate-700/40 text-slate-300 border-slate-600";
};

export function StatusBadge({ status }) {
  return (
    <div className={`rounded-xl border px-4 py-3 ${getStatusClass(status)}`}>
      <div className="flex items-center gap-2 text-lg font-semibold">
        {status === "БРАК" ? <AlertTriangle /> : <CheckCircle2 />}
        {status}
      </div>
    </div>
  );
}
