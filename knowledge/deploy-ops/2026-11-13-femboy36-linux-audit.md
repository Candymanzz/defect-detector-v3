# Linux на femboy36 — проверка 2026-11-13

## Границы проверки

Read-only SSH от пользователя `o`, около 09:00–09:03 Europe/Minsk.
На сервере конфиги, пакеты, службы и данные не менялись; перезапусков не было.
Локальный checkout: `boobs+pasha / c3b77bbf`; существующая правка `.idea/workspace.xml` не затронута.
Это снимок состояния, а не нагрузочное испытание или проверка полного цикла станка.

## ОС и оборудование

- SSH alias `femboy36`, фактический hostname `o`.
- Ubuntu 24.04.4 LTS, x86_64, `6.8.1-1015-realtime`, PREEMPT_RT.
- Intel Core i7-8700: 6 физических ядер / 12 логических CPU, SMT включён.
- RAM около 16 GB (15 GiB); на срезе доступно 7.9 GiB.
- Swap `/swap.img` 4 GiB, занято около 1 GiB; `vm.swappiness=60`.
  В двух секундных интервалах vmstat активного swap-in/swap-out не было.
- Системный SSD `sda` 119.2 GiB, корень ext4: 116 GiB, занято 104 GiB,
  доступно 6.6 GiB, использование **95%**. EFI 1 GiB.
- Второй диск Toshiba MQ04ABD200 1.8 TiB с NTFS-разделом не смонтирован.
- Uptime около 3 дней 20 часов. Europe/Minsk; NTP активен, часы синхронизированы.

## CPU и задержки

В `/etc/default/grub.d/90-iml-cpu-isolation.cfg` заданы и подтверждены
`/proc/cmdline`:

```text
isolcpus=domain,managed_irq,2-5,8-11
nohz_full=2-5,8-11
rcu_nocbs=2-5,8-11
irqaffinity=0-1,6-7
kthread_cpus=0-1,6-7
```

Приложению выделены физические ядра 2–5 с SMT-парами 8–11;
системе оставлены ядра 0–1 с парами 6–7. В наблюдаемых счётчиках
`/proc/interrupts` IRQ сетевых интерфейсов приходились на CPU0,1,6,7.
`irqbalance` unit отсутствует. `cpu-performance.service` enabled/active;
governor CPU0 и power profile — `performance`.
В `99-realtime.cfg` задан приоритет realtime flavour; меню GRUB включено на 5 секунд.
Основной `/etc/default/grub` сам по себе не содержит параметров изоляции —
при проверке необходимо учитывать drop-in файлы.

Backend стартует через `run-rt.sh` с `IML_CPUSET=2-5,8-11` и `taskset`.
Affinity потоков Java/C++ подтверждена выборочным выводом `taskset -apc`.
C++ получает дополнительный аргумент `--inspect-cpus 3-5`;
фактические affinity всех inspection-потоков отдельно не сопоставлялись.
Все 326 наблюдаемых потоков основных Java/C++ процессов имеют класс TS:
RT-ядро и CPU isolation здесь не означают SCHED_FIFO/RR для приложения.
Лимиты службы: `LimitRTPRIO=0`, `LimitMEMLOCK=8388608`.

## Сеть

NetworkManager управляет шестью Ethernet-интерфейсами. MTU всех Ethernet — 1500.
`ethtool` подтвердил 1000 Mb/s Full Duplex на enp1s0 и enp5s0;
часть netlink-запроса недоступна без дополнительных прав.

| Интерфейс | IPv4 хоста |
|---|---|
| enp1s0 | 169.254.89.10/24, 169.254.81.10/24 |
| enp2s0 | 169.254.93.10/24, 169.254.96.10/24 |
| enp3s0 | 169.254.101.10/24, 169.254.92.10/24 |
| enp4s0 | 169.254.57.10/24, 169.254.99.10/24 |
| enp5s0 | 169.254.95.10/24, 169.254.90.10/24 |
| enp0s31f6 | 169.254.220.31/24 |

Пять отдельных интерфейсов обслуживают десять камерных подсетей.
Default route: через `169.254.220.21` на enp0s31f6, получен DHCP.
Tailscale активен, IPv4 `100.122.121.36`; также активны Windscribe helper и
`tun0` с `172.16.219.2/32`. Policy routing VPN подробно не проверялся.
IPv4 forwarding выключен. `rmem_max`/`wmem_max` — 10485760 байт.
На каждом камерном интерфейсе накоплено 161–168 RX errors с начала работы;
это не измерение текущей частоты ошибок и не доказательство потери кадров.

## Автозапуск приложения и экрана

`defect-detector.service` работает от `o` из `/home/o/Документы/core-dev`,
зависит от network-online и доступности каталога. Команда запуска:
`bash .../run-rt.sh --no-frontend`. `Restart=on-failure`, задержка 15 секунд,
лимит 5 запусков за 300 секунд; вывод в journal.
Drop-in `20-mvs-environment.conf` задаёт пути SDK в `/opt/MVS`.

На срезе служба active с 2026-11-13 08:33:50 +03, MainPID209744,
NRestarts=0. Основная Java PID209823, C++ PID209866, отдельный MVS IO worker
Java PID210229. JVM использует Java17, Xms256m/Xmx1024m.
GET `127.0.0.1:8099/health` → `ok`, GET `127.0.0.1:8000/health` → status ok.
Java HTTP8099, WS8765 и C++8000 слушают loopback.

Система загружается в graphical.target. GDM автоматически входит как `kiosk`;
есть отдельная X11 session `Defect Detector Kiosk`.
`/usr/local/bin/defect-detector-kiosk` запускает Electron из
`/opt/defect-detector-ui` без desktop shell/обычного window manager,
выключает screensaver/DPMS и повторяет запуск после выхода с паузой 5 секунд.
Журналы киоска — `~/.local/state/defect-detector/`.

## Доступ и фоновые службы

- SSH22 слушает IPv4/IPv6 wildcard; соединение ключом от `o` успешно.
- `o` входит в sudo; общий sudo требует пароль, NOPASSWD разрешён только
  helper `/usr/local/sbin/defect-detector-restart-ui` без аргументов.
- `kiosk-vnc.service`: x11vnc на loopback5900, текущий X11 display :0,
  `-nopw`, `-forever`, `-shared`; доступ предполагает туннель/локальное подключение.
- xrdp слушает wildcard3389, GNOME Remote Desktop тоже active.
- В `/etc/ufw/ufw.conf` `ENABLED=no`. Это не проверка всех nftables/iptables
  правил или доступности портов извне; root-правила firewall не запрашивались.
- GNOME/GDM, CUPS, Avahi, ModemManager, snapd, fwupd, unattended-upgrades,
  Tailscale и Windscribe helper активны. Есть пользовательские VS Code и Firefox.
- apt-daily/apt-daily-upgrade timers enabled; автоматическое обновление
  списков и unattended upgrades включено. `/var/run/reboot-required` существует
  (linux-image-7.0.0-43-generic, linux-base, libc6); перезагрузка не выполнялась.
- systemctl --failed: пусто. В journal присутствуют ошибки GDM/GLib;
  их влияние на работу экрана не проверялось визуально.

## Расхождения с прежней базой и открытые вопросы

Запись о деплое 6d8cad99 от 2026-11-09 историческая: теперь серверный checkout
`boobs+pasha / 2b165d9d647dba351f62a519f6ebdd1f4ae08a08`, с локальными
изменениями `cppAnalisSurface/src/core/settings.cpp` и `deployed-version.txt`.
HEAD исходников не доказывает идентичность работающего бинарника;
хеши сборок и происхождение этих правок этой задачей не проверялись.

Ближайший эксплуатационный вопрос — запас места на корне (6.6 GiB).
Очистка, перенос данных, обновления, изменение firewall и CPU-настроек
не выполнялись. Не проверялись SMART, полный firewall, визуальный экран,
физический захват/PLC, задержки под нагрузкой и срок цикла 500 мс.
