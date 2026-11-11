# Обновление установленных станочных ПК

## Исправление исчезающих стримов после выхода из SSH

Для установленного выпуска с удалёнными SHM-буферами:

```bash
ansible-playbook -i deploy/ansible/inventory.ini deploy/ansible/fix-ipc.yml --limit femboy36 --ask-become-pass
```

Playbook задаёт `/etc/systemd/logind.conf.d/90-defect-detector-ipc.conf` с
RemoveIPC=no, делает reload logind без перезапуска сессий, проверяет действующее
значение через D-Bus и перезапускает backend для пересоздания удалённых SHM.
Киоск не останавливается. Применение требует допустимого состояния оборудования
для перезапуска backend. После выхода всех SSH-сессий повторно проверьте стрим.

Изменение RemoveIPC действует на весь ПК: IPC остальных обычных пользователей
тоже больше не очищается при logout. Для выделенного станочного ПК выбрана
явная политика сохранения IPC. Отдельный системный пользователь службы —
альтернатива с миграцией прав/данных, она в этой задаче не выполняется.

Настройка применяется перед каждым deployment. После запуска и при откате
проверяются читаемые непустые SHM всех ID из /api/cameras от пользователя
приложения. Это обнаруживает данный отказ, но не доказывает свежесть кадров
или аппаратную готовность всей инспекции. Нельзя подменять этой проверкой
контрольную инспекцию. В Ubuntu на femboy36 CanReload=yes подтверждён; если
другой ПК не поддерживает reload, playbook завершится ошибкой без автоматического
перезапуска logind или графической сессии.

Это обновление существующей установки, а не подготовка пустого Linux.
По умолчанию выбран выпуск `defect-detector-22-32b59656`.
Параметры и списки файлов находятся в `release-vars.yml`.

## Проверка без остановки

```bash
ansible-playbook -i deploy/ansible/inventory.ini deploy/ansible/deploy-release.yml --syntax-check
python3 deploy/ansible/test_release.py
ansible-playbook -i deploy/ansible/inventory.ini deploy/ansible/validate-release.yml --limit femboy36 --ask-become-pass
```

Первые две команды не обращаются к станку. Вторая проверяет настоящие задачи
backup/install/restore на временных файлах в `/tmp`, в том числе намеренную
ошибку после установки, возврат удалённых/добавленных файлов, сохранность
ips.yaml, node_modules, данных C++ и режима 0600. Также проверяются действующий
и устаревший идентификаторы безобидного приостановленного shell-процесса.
Это не испытание systemd, оборудования или потери SSH на настоящем ПК.

`validate-release.yml` пересоздаёт только распакованные каталоги выпуска в
`/tmp`, проверяет SHA256, `ldd -r`, конфиги новым JAR, наличие файлов,
WorkingDirectory службы, HTTP и цикл киоска. Для чтения чужих процессов
понадобится sudo. Приложение не останавливается.
Не запускайте подготовку и установку одновременно из разных терминалов.

## Установка

Перед запуском оборудование должно находиться в согласованном состоянии
для остановки приложения и возможного изменения его аппаратных выходов.

```bash
ansible-playbook -i deploy/ansible/inventory.ini deploy/ansible/deploy-release.yml --limit femboy36 --ask-become-pass
```

На ПК атомарно создаётся lock-каталог `/var/lib/defect-detector-deploy.lock`.
Внутри блокировки повторяются подготовка и проверки. Затем выполняются
stop → backup → install → start-and-check; при ошибке — rollback с итоговым
ошибочным статусом, даже если восстановление прошло успешно.
Статические import_tasks позволяют --syntax-check разобрать все задачи.
--check не используется для имитации установки.

Резервная копия в `/var/backups/defect-detector-<выпуск>-<суффикс>/`
содержит before.tar, его SHA256 и paths.json. Сохраняются config целиком,
бинарники, заменяемые файлы UI и `cppAnalisSurface/data` (текущий --data-dir).
Перед восстановлением проверяется SHA256. Установка сохраняет ips.yaml,
config/data и node_modules. Откат возвращает снимок config и данных C++:
изменения после его создания будут потеряны. Другие каталоги данных и
команды, уже отправленные оборудованию, резервная копия не восстанавливает.
Если меняется working_dir/--data-dir, обновите список backup_paths.

Служба и скрипты запуска не обновляются этим пакетом. Путь установки и
WorkingDirectory проверяются до остановки; другая схема требует адаптации.
Electron runtime остаётся установленным: изменение его версии или новых
runtime-зависимостей требует отдельного обновления node_modules.

`/health` Java в текущем коде возвращает текст ok; наличие HTTP и Electron
не подтверждает камеры, свет, IO или правильность инспекции. После установки
нужно проверить экран, оборудование и контрольную инспекцию. Полный запуск
backend по существующей логике очищает дообученные нормы.

При потере SSH/питания Ansible не гарантирует rescue/always. Тогда lock может
остаться; нельзя удалять его, пока не проверены отсутствие другого deployment,
служба, процессы киоска и резервная копия. Не удаляйте .kiosk-paused.json:
он нужен stop-dev.sh --resume-kiosk для восстановления цикла.

## Автоматическое обновление через GitLab

CI содержит check-ansible и deploy-femboy36. Deployment запускается автоматически
только после успешного push pipeline на защищённой основной ветке (CI_DEFAULT_BRANCH), выбранной владельцем: boobs+pasha. Для pipeline,
запущенного кнопкой Run pipeline на этой ветке, deployment остаётся ручным.
Рабочие ветки, merge request и теги приложение не обновляют. Ошибка deployment
делает pipeline неуспешным (allow_failure:false).

needs получает пакет из package-release того же pipeline; release_name
вычисляется из CI_PIPELINE_IID и CI_COMMIT_SHORT_SHA. resource_group сериализует
deployment, interruptible:false предотвращает автоматическую отмену идущего
обновления. Перед действиями на станке git ls-remote проверяет, что CI_COMMIT_SHA
всё ещё вершина основной ветки; устаревший pipeline прекращается с ошибкой. Секретный
URL репозитория и stderr этой проверки не выводятся. Проверка не предотвращает
появление нового коммита уже после её прохождения: такой выпуск установится
следующим после текущей транзакции.

### Подключение проекта владельцем

На 2026-11-10 отдельный ed25519 deployment-ключ создан и его публичная часть
добавлена пользователю o на femboy36 с опцией restrict (без PTY, forwarding и
user-rc). Вход этим ключом из Docker node:24-bookworm проверен, служба active.
Сudo требует пароль. Личные SSH-ключи хоста не монтируются в CI.

Материалы для импорта переменных находятся локально в игнорируемой папке
`.tmp/gitlab-deploy/` с режимом каталога 0700 и приватного ключа 0600.
Git check-ignore подтвердил исключение файлов. Не используйте git add -f для
этой папки; приватный ключ не нужно отправлять в чат или коммитить.

В GitLab → Settings → CI/CD → Variables добавьте следующие переменные.
Для всех: Protected и Environment scope = factory/femboy36.

| Key | Type | Value |
| --- | --- | --- |
| SSH_PRIVATE_KEY | File | Всё содержимое .tmp/gitlab-deploy/id_ed25519, включая завершающий перевод строки |
| SSH_KNOWN_HOSTS | File | Содержимое .tmp/gitlab-deploy/known_hosts |
| DEPLOY_INVENTORY | File | Содержимое .tmp/gitlab-deploy/inventory.ini |
| ANSIBLE_BECOME_PASSWORD | Variable | sudo-пароль пользователя o на femboy36 |

Для sudo-пароля выберите Masked/Hidden, если доступно. Многострочный SSH-ключ
может не проходить ограничения Masked: File + Protected + environment scope
остаются обязательными. Expand variable reference выключите. Не включайте
CI_DEBUG_TRACE. Тип File означает, что в Value вставляется содержимое файла,
а не локальный путь. Пароль в репозиторий не записывается.

Known_hosts получен из публичного host key сервера через уже доверенное SSH-
подключение. Проверка ключей включена; ssh-keyscan на лету не используется.
Docker TCP22 проверен. Перед первым запуском нужно отдельно пройти Ansible
из CI с этими переменными; факт сетевого доступа не подтверждает sudo-пароль.

После импорта переменных:

1. Закоммитьте файлы .gitlab-ci.yml, deploy/ansible и заметки knowledge в рабочей
   ветке, проверьте git diff --cached. Не добавляйте локальный config/ips.yaml.
2. В GitLab Settings → Repository → Branch defaults выберите Default branch =
   boobs+pasha и сохраните. Если ветки ещё нет на GitLab, сначала отправьте её.
3. Settings → Repository → Branch rules: защитите boobs+pasha, запретите force push,
   разрешите push/merge только тем, кто может выпускать обновления.
4. Первый запуск можно выполнить Build → Pipelines → New pipeline → boobs+pasha,
   затем нажать Play на deploy-femboy36 после успешной сборки. Это реальная установка.
5. Последующие push в защищённую основную ветку запускают CI → package → deployment
   автоматически. CI_DEFAULT_BRANCH используется и в правилах job, и в проверке
   актуального коммита. Изменение основной ветки в GitLab меняет источник deployment.

Из локальной ветки boobs+pasha:

```bash
git push gitlab 'boobs+pasha'
```

Git отклонит не-fast-forward обновление, если истории разошлись:
сначала объедините изменения, не используйте force push для обхода.

Истёкшие artifacts недоступны: потребуется новый успешный pipeline.
Изначально ci-deploy.py разрешает только femboy36. femboy15 нужно отдельно
включить и проверить, прежде чем разрешать общее обновление. При serial:1
ошибка второго ПК не откатывает первый. Потеря SSH/питания может прервать rescue.

Официальные описания: [rules](https://docs.gitlab.com/ci/jobs/job_rules/),
[переменные](https://docs.gitlab.com/ci/variables/),
[SSH в CI](https://docs.gitlab.com/ci/jobs/ssh_keys/),
[ограничения rescue/always](https://docs.ansible.com/ansible/latest/playbook_guide/playbooks_blocks.html).
