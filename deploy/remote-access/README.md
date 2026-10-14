# Доступ к экрану kiosk с Windows

Нужны подключённый Tailscale, Windows OpenSSH и TigerVNC Viewer.
Linux должен быть доступен по адресу `100.122.121.36`, а x11vnc — работать на порту 5900.

## Быстрое подключение

Откройте `connect-kiosk.cmd` двойным щелчком. В новом терминале подтвердите
ключ сервера при первом подключении и введите пароль Linux-пользователя `o`.
После создания туннеля автоматически откроется TigerVNC.
Терминал SSH оставьте открытым; закройте его после завершения работы.

Для другого пользователя запустите из PowerShell:

```powershell
.\connect-kiosk.cmd -SshUser operator1
```

Если локальный порт занят, можно указать другой:

```powershell
.\connect-kiosk.cmd -SshUser operator1 -LocalPort 15901
```

Каждому человеку нужен собственный доступ через Tailscale и существующая
учётная запись Linux. Эти файлы не создают пользователей и не предоставляют доступ.
Пароли в файлах не хранятся. Все подключения управляют одним экраном kiosk;
переключение физического экрана на Wayland-сессию `o` не меняет экран VNC.

## Если x11vnc не запущен

На Linux в отдельной SSH-сессии:

```bash
sudo -u kiosk x11vnc -display :0 -auth /run/user/1001/gdm/Xauthority -localhost -rfbport 5900 -forever -shared -nopw
```

Оставьте эту сессию открытой. Команда соответствует текущему дисплею и UID kiosk.

## Ручное подключение

Добавьте блок из `ssh-config.example` в `%USERPROFILE%\.ssh\config`, затем:

```powershell
ssh.exe -N detector-kiosk
```

После входа откройте `kiosk.tigervnc` через TigerVNC Viewer.
Параметр `Shared=1` разрешает совместное подключение нескольких клиентов.
