import { useCallback, useEffect, useRef, useState } from "react";
import { orchestratorApi } from "../../shared/api";
import type { PlcSignalState, PlcStatusResponse, PlcTimeoutState } from "../../shared/api/types";
import { errorMessage } from "../../shared/lib/errors";
import { Button } from "../../shared/ui/Button";
import { orchestratorWs } from "../../shared/ws";
import type { PlcFinsTrafficPayload } from "../../shared/ws";
import "./PlcPanel.css";

const MAX_TRAFFIC_ENTRIES = 300;
const STATUS_POLL_MS = 1500;

type PlcPanelProps = {
  isOpen: boolean;
  onClose: () => void;
};

type TrafficEntry = PlcFinsTrafficPayload & {
  id: string;
};

export function PlcPanel({ isOpen, onClose }: PlcPanelProps) {
  const [status, setStatus] = useState<PlcStatusResponse | null>(null);
  const [timeouts, setTimeouts] = useState<PlcTimeoutState[]>([]);
  const [draftUnits, setDraftUnits] = useState<Record<string, string>>({});
  const [statusError, setStatusError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busySignal, setBusySignal] = useState<string | null>(null);
  const [timeoutsBusy, setTimeoutsBusy] = useState(false);
  const [traffic, setTraffic] = useState<TrafficEntry[]>([]);
  const logEndRef = useRef<HTMLDivElement | null>(null);
  const autoScrollRef = useRef(true);

  const applyTimeouts = useCallback((next: PlcTimeoutState[]) => {
    setTimeouts(next);
    setDraftUnits(
      Object.fromEntries(next.map((item) => [item.name, String(item.valueUnits)])),
    );
  }, []);

  const refreshStatus = useCallback(async () => {
    try {
      const next = await orchestratorApi.getPlcStatus();
      setStatus(next);
      setStatusError(null);
    } catch (error) {
      setStatusError(errorMessage(error));
    }
  }, []);

  const refreshTimeouts = useCallback(async () => {
    try {
      const next = await orchestratorApi.getPlcTimeouts();
      applyTimeouts(next.timeouts ?? []);
      setStatusError(null);
    } catch (error) {
      setStatusError(errorMessage(error));
    }
  }, [applyTimeouts]);

  useEffect(() => {
    if (!isOpen) {
      return;
    }

    void refreshStatus();
    void refreshTimeouts();
    const timer = window.setInterval(() => {
      void refreshStatus();
    }, STATUS_POLL_MS);

    const unsubscribe = orchestratorWs.onMessage((message) => {
      if (message.type !== "server.plc_fins_traffic") {
        return;
      }
      const entry: TrafficEntry = {
        ...message.payload,
        id: `${message.payload.server_ts_ms}-${message.message_id ?? Math.random().toString(16).slice(2)}`,
      };
      setTraffic((current) => {
        const next = [...current, entry];
        return next.length > MAX_TRAFFIC_ENTRIES ? next.slice(next.length - MAX_TRAFFIC_ENTRIES) : next;
      });
    });

    return () => {
      window.clearInterval(timer);
      unsubscribe();
    };
  }, [isOpen, refreshStatus, refreshTimeouts]);

  useEffect(() => {
    if (!isOpen || !autoScrollRef.current) {
      return;
    }
    logEndRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [isOpen, traffic]);

  if (!isOpen) {
    return null;
  }

  const editable = Boolean(status?.editable);
  const inspectionLocked = Boolean(status?.inspection_enabled || status?.inspection_in_flight);
  const signals = status?.signals ?? [];
  const visionReady = signals.find((signal) => signal.name === "vision_ready")?.lastValue;

  const handleWriteSignal = async (signal: PlcSignalState, value: boolean, pulse: boolean) => {
    if (!editable || busySignal) {
      return;
    }
    setBusySignal(signal.name);
    setActionError(null);
    try {
      const response = await orchestratorApi.writePlcSignal({
        signal: signal.name,
        value,
        pulse,
      });
      setStatus((current) =>
        current
          ? {
              ...current,
              ...response,
              signals: response.signals,
            }
          : {
              ok: true,
              enabled: response.enabled,
              inspection_in_flight: response.inspection_in_flight,
              inspection_enabled: response.inspection_enabled,
              editable: response.editable,
              signals: response.signals,
            },
      );
    } catch (error) {
      setActionError(errorMessage(error));
      void refreshStatus();
    } finally {
      setBusySignal(null);
    }
  };

  const handleSaveTimeouts = async () => {
    if (!editable || timeoutsBusy) {
      return;
    }
    const payload: Record<string, number> = {};
    for (const item of timeouts) {
      const raw = draftUnits[item.name];
      const units = Number.parseInt(String(raw ?? "").trim(), 10);
      if (!Number.isFinite(units) || units < 0 || units > 9999) {
        setActionError(`${item.address}: укажите целое 0…9999 (единицы 100 ms, BCD)`);
        return;
      }
      if (units !== item.valueUnits) {
        payload[item.name] = units;
      }
    }
    if (Object.keys(payload).length === 0) {
      setActionError("Нет изменений таймингов");
      return;
    }
    setTimeoutsBusy(true);
    setActionError(null);
    try {
      const response = await orchestratorApi.putPlcTimeouts(payload);
      applyTimeouts(response.timeouts ?? []);
      if (response.signals) {
        setStatus((current) =>
          current
            ? {
                ...current,
                editable: response.editable,
                inspection_enabled: response.inspection_enabled,
                inspection_in_flight: response.inspection_in_flight,
                signals: response.signals ?? current.signals,
              }
            : current,
        );
      }
      void refreshStatus();
    } catch (error) {
      setActionError(errorMessage(error));
      void refreshTimeouts();
    } finally {
      setTimeoutsBusy(false);
    }
  };

  return (
    <div className="plc-panel-backdrop" role="presentation" onClick={onClose}>
      <section
        className="plc-panel"
        role="dialog"
        aria-modal="true"
        aria-label="ПЛК FINS"
        onClick={(event) => event.stopPropagation()}
      >
        <header className="plc-panel__header">
          <div>
            <h2>ПЛК · FINS</h2>
            <p className="plc-panel__subtitle">
              Тайминги D4400–D4404, сигналы и трафик. vision_ready пишется при готовности оркестратора
              (камеры подняты), не при старте инспекции.
            </p>
          </div>
          <Button type="button" onClick={onClose}>
            Закрыть
          </Button>
        </header>

        <div className="plc-panel__status-row">
          <StatusChip label="FINS" value={status?.enabled ? "вкл" : "выкл"} tone={status?.enabled ? "ok" : "warn"} />
          <StatusChip
            label="vision_ready"
            value={visionReady == null ? "?" : visionReady ? "1" : "0"}
            tone={visionReady ? "ok" : "warn"}
          />
          <StatusChip
            label="Эталон"
            value={status?.inspection_enabled ? "задан" : "нет"}
            tone={status?.inspection_enabled ? "warn" : "ok"}
          />
          <StatusChip
            label="Цикл"
            value={status?.inspection_in_flight ? "в полёте" : "нет"}
            tone={status?.inspection_in_flight ? "warn" : "ok"}
          />
          <StatusChip
            label="Ручное управление"
            value={editable ? "разрешено" : "заблокировано"}
            tone={editable ? "ok" : "warn"}
          />
        </div>

        {inspectionLocked && (
          <p className="plc-panel__lock-note">
            Пока задан эталон или идёт цикл, тайминги и ручные сигналы заблокированы. Сбросьте эталон,
            чтобы править D4400–D4404.
          </p>
        )}
        {statusError && <p className="plc-panel__error">Статус: {statusError}</p>}
        {actionError && <p className="plc-panel__error">{actionError}</p>}

        <section className="plc-panel__timeouts">
          <header className="plc-panel__section-header">
            <h3>Тайминги (DM, BCD, ×100 ms)</h3>
            <div className="plc-panel__section-actions">
              <Button type="button" onClick={() => void refreshTimeouts()} disabled={timeoutsBusy}>
                Прочитать
              </Button>
              <Button type="button" onClick={() => void handleSaveTimeouts()} disabled={!editable || timeoutsBusy}>
                Записать
              </Button>
            </div>
          </header>
          <div className="plc-panel__timeout-list">
            {timeouts.length === 0 && <p className="plc-panel__empty">Тайминги не загружены</p>}
            {timeouts.map((item) => (
              <label key={item.name} className="plc-panel__timeout-row">
                <div className="plc-panel__timeout-meta">
                  <strong>
                    {item.address} · {item.name}
                  </strong>
                  <span>{item.description || "—"}</span>
                  <span>
                    сейчас {item.valueUnits} ед. = {item.valueMs} ms (raw=0x{item.rawWord.toString(16)})
                  </span>
                </div>
                <input
                  className="plc-panel__timeout-input"
                  type="number"
                  min={0}
                  max={9999}
                  step={1}
                  disabled={!editable || timeoutsBusy}
                  value={draftUnits[item.name] ?? ""}
                  onChange={(event) => {
                    const value = event.target.value;
                    setDraftUnits((current) => ({ ...current, [item.name]: value }));
                  }}
                />
              </label>
            ))}
          </div>
        </section>

        <div className="plc-panel__body">
          <section className="plc-panel__signals">
            <header className="plc-panel__section-header">
              <h3>Сигналы</h3>
              <Button type="button" onClick={() => void refreshStatus()} disabled={Boolean(busySignal)}>
                Обновить
              </Button>
            </header>
            <div className="plc-panel__signal-list">
              {signals.length === 0 && <p className="plc-panel__empty">Сигналы не загружены</p>}
              {signals.map((signal) => (
                <article key={signal.name} className="plc-panel__signal-card">
                  <div className="plc-panel__signal-meta">
                    <strong>{signal.name}</strong>
                    <span>
                      {signal.area}
                      {signal.address} · {signal.description || "—"}
                    </span>
                    <span data-value={String(signal.lastValue)}>
                      last={signal.lastValue == null ? "?" : signal.lastValue ? "1" : "0"}
                    </span>
                  </div>
                  <div className="plc-panel__signal-actions">
                    <Button
                      type="button"
                      disabled={!editable || busySignal === signal.name}
                      onClick={() => void handleWriteSignal(signal, true, false)}
                    >
                      On
                    </Button>
                    <Button
                      type="button"
                      disabled={!editable || busySignal === signal.name}
                      onClick={() => void handleWriteSignal(signal, false, false)}
                    >
                      Off
                    </Button>
                    <Button
                      type="button"
                      variant="warning"
                      disabled={!editable || busySignal === signal.name}
                      onClick={() => void handleWriteSignal(signal, true, true)}
                    >
                      Pulse
                    </Button>
                  </div>
                </article>
              ))}
            </div>
          </section>

          <section className="plc-panel__traffic">
            <header className="plc-panel__section-header">
              <h3>Трафик ПЛК (FINS + DI)</h3>
              <Button type="button" onClick={() => setTraffic([])}>
                Очистить
              </Button>
            </header>
            <div
              className="plc-panel__traffic-log"
              onScroll={(event) => {
                const el = event.currentTarget;
                const distance = el.scrollHeight - el.scrollTop - el.clientHeight;
                autoScrollRef.current = distance < 48;
              }}
            >
              {traffic.length === 0 && (
                <p className="plc-panel__empty">
                  Пока нет сообщений. Здесь FINS (D4400…) и дискретные DO→DI (ready/fault/reject).
                  Лог только пока окно открыто.
                </p>
              )}
              {traffic.map((entry) => (
                <div
                  key={entry.id}
                  className="plc-panel__traffic-row"
                  data-direction={entry.direction}
                  data-ok={entry.ok ? "true" : "false"}
                  data-op={entry.operation}
                >
                  <span className="plc-panel__traffic-time">{formatTs(entry.server_ts_ms)}</span>
                  <span className="plc-panel__traffic-dir">{entry.direction}</span>
                  <span className="plc-panel__traffic-op">
                    {entry.operation === "discrete_di" ? "DI" : entry.operation}
                  </span>
                  <span className="plc-panel__traffic-addr">
                    {entry.signal ? `${entry.signal} · ` : ""}
                    {entry.area}
                    {entry.area === "DO→DI" ? " " : ""}
                    {entry.address}
                  </span>
                  <span className="plc-panel__traffic-value">{formatValue(entry.value)}</span>
                  <span className="plc-panel__traffic-meta">
                    {entry.sid != null ? `sid=${entry.sid}` : ""}
                    {entry.end_code ? ` end=${entry.end_code}` : ""}
                    {entry.error ? ` err=${entry.error}` : ""}
                    {entry.ok ? "" : " FAIL"}
                  </span>
                  {entry.hex_frame && entry.operation !== "discrete_di" ? (
                    <code className="plc-panel__traffic-hex">{entry.hex_frame}</code>
                  ) : null}
                  {entry.hex_frame && entry.operation === "discrete_di" && entry.direction === "response" ? (
                    <code className="plc-panel__traffic-hex">{entry.hex_frame}</code>
                  ) : null}
                </div>
              ))}
              <div ref={logEndRef} />
            </div>
          </section>
        </div>
      </section>
    </div>
  );
}

function StatusChip({
  label,
  value,
  tone,
}: {
  label: string;
  value: string;
  tone: "ok" | "warn";
}) {
  return (
    <div className="plc-panel__chip" data-tone={tone}>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function formatTs(serverTsMs: number) {
  try {
    return new Date(serverTsMs).toLocaleTimeString("ru-RU", {
      hour12: false,
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      fractionalSecondDigits: 3,
    } as Intl.DateTimeFormatOptions);
  } catch {
    return String(serverTsMs);
  }
}

function formatValue(value: unknown) {
  if (value == null) {
    return "—";
  }
  if (typeof value === "boolean") {
    return value ? "1" : "0";
  }
  if (typeof value === "number" || typeof value === "string") {
    return String(value);
  }
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}
