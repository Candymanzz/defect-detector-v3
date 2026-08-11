using System;
using System.Collections.Generic;

namespace ImlLauncher
{
    internal enum ServiceState
    {
        Waiting,
        Starting,
        Ready,
        Error,
        Skipped
    }

    internal sealed class ServiceItem
    {
        public string Id { get; private set; }
        public string Title { get; private set; }
        public ServiceState State { get; set; }
        public string Detail { get; set; }
        public bool Critical { get; private set; }
        public bool Soft { get; private set; }

        public ServiceItem(string id, string title, bool critical, bool soft)
        {
            Id = id;
            Title = title;
            Critical = critical;
            Soft = soft;
            State = ServiceState.Waiting;
            Detail = "";
        }

        public string StateLabel
        {
            get
            {
                switch (State)
                {
                    case ServiceState.Waiting:
                        return "Ожидание";
                    case ServiceState.Starting:
                        return "Запуск…";
                    case ServiceState.Ready:
                        return "Готов";
                    case ServiceState.Error:
                        return "Ошибка";
                    case ServiceState.Skipped:
                        return "Пропущен";
                    default:
                        return "?";
                }
            }
        }
    }

    internal static class ServiceIds
    {
        public const string Environment = "env";
        public const string Orchestrator = "orchestrator";
        public const string AnalisSurface = "analis";
        public const string LightServer = "light";
        public const string IoInput = "io";
        public const string Frontend = "frontend";
        public const string ClientWs = "ws";
        public const string Workers = "workers";
    }

    internal sealed class ServiceStatusModel
    {
        private readonly List<ServiceItem> _items = new List<ServiceItem>();
        private readonly Dictionary<string, ServiceItem> _byId =
            new Dictionary<string, ServiceItem>(StringComparer.OrdinalIgnoreCase);

        public IList<ServiceItem> Items
        {
            get { return _items; }
        }

        public string StepText { get; set; }

        public ServiceStatusModel(bool includeFrontend)
        {
            StepText = "Инициализация…";
            Add(ServiceIds.Environment, "Проверка окружения", true, false);
            Add(ServiceIds.Orchestrator, "Оркестратор", true, false);
            Add(ServiceIds.AnalisSurface, "analisSurface", true, false);
            Add(ServiceIds.LightServer, "LightServer", false, true);
            Add(ServiceIds.IoInput, "IoInputMonitor", false, true);
            if (includeFrontend)
            {
                Add(ServiceIds.Frontend, "Frontend (UI)", true, false);
            }
            else
            {
                ServiceItem skipped = Add(ServiceIds.Frontend, "Frontend (UI)", false, false);
                skipped.State = ServiceState.Skipped;
                skipped.Detail = "--no-frontend";
            }
            Add(ServiceIds.ClientWs, "Client WebSocket", false, true);
            Add(ServiceIds.Workers, "Рабочие процессы", false, true);
        }

        private ServiceItem Add(string id, string title, bool critical, bool soft)
        {
            ServiceItem item = new ServiceItem(id, title, critical, soft);
            _items.Add(item);
            _byId[id] = item;
            return item;
        }

        public ServiceItem Get(string id)
        {
            ServiceItem item;
            return _byId.TryGetValue(id, out item) ? item : null;
        }

        public void Set(string id, ServiceState state, string detail)
        {
            ServiceItem item = Get(id);
            if (item == null)
            {
                return;
            }
            if (item.State == ServiceState.Skipped)
            {
                return;
            }
            // Don't downgrade Ready/Error on soft flaps except Starting→Ready and Waiting→Starting.
            if (item.State == ServiceState.Ready && state == ServiceState.Starting)
            {
                return;
            }
            if (item.State == ServiceState.Error && state != ServiceState.Ready && state != ServiceState.Error)
            {
                return;
            }
            item.State = state;
            if (detail != null)
            {
                item.Detail = detail;
            }
        }

        public int ProgressPercent
        {
            get
            {
                bool criticalReady = CriticalReady;
                int total = 0;
                int done = 0;
                for (int i = 0; i < _items.Count; i++)
                {
                    ServiceItem item = _items[i];
                    if (item.State == ServiceState.Skipped)
                    {
                        continue;
                    }
                    total++;
                    if (item.State == ServiceState.Ready || item.State == ServiceState.Error)
                    {
                        done++;
                    }
                    else if (item.Soft && criticalReady && item.State == ServiceState.Starting)
                    {
                        // Soft probes may never answer; don't hold the bar after critical path is up.
                        done++;
                    }
                }
                if (total == 0)
                {
                    return 0;
                }
                return (int)Math.Round(100.0 * done / total);
            }
        }

        public bool CriticalReady
        {
            get
            {
                for (int i = 0; i < _items.Count; i++)
                {
                    ServiceItem item = _items[i];
                    if (!item.Critical)
                    {
                        continue;
                    }
                    if (item.State != ServiceState.Ready)
                    {
                        return false;
                    }
                }
                return true;
            }
        }

        public bool HasCriticalError
        {
            get
            {
                for (int i = 0; i < _items.Count; i++)
                {
                    ServiceItem item = _items[i];
                    if (item.Critical && item.State == ServiceState.Error)
                    {
                        return true;
                    }
                }
                return false;
            }
        }
    }
}
