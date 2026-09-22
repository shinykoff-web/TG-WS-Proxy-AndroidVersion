# TG WS Proxy Android V0.1

Первая экспериментальная Android-обвязка над Python-ядром TG WS Proxy 1.10.4.

## Что сделано

- Android UI с одной кнопкой запуска/остановки.
- Foreground Service, чтобы прокси продолжал работать после ухода с экрана.
- Встроенный Python через Chaquopy 17.
- Оригинальный каталог `proxy/` из присланного TG WS Proxy 1.10.4.
- Локальный listener: `127.0.0.1:1443`.
- Secret генерируется один раз и сохраняется в SharedPreferences.

## Что пока НЕ сделано

- Автозапуск после перезагрузки телефона.
- Автоматическое изменение настроек Telegram.
- Готовая проверка реального подключения Telegram.
- Красивый UI.
- Полная обработка всех Android lifecycle/энергосбережения сценариев.

## Сборка

Открой папку проекта в Android Studio и дождись синхронизации Gradle.
Нужен Android SDK и JDK 17.

Chaquopy 17 использует Python 3.13 в этом проекте.


## V0.1 status fix
Статус приложения теперь обновляется только после подтверждения, что `127.0.0.1:1443` действительно принимает TCP-соединение. Ошибка запуска передаётся обратно на экран приложения.


## V0.1-debug3
Расширенный режим диагностики:
- отдельный preflight перед запуском;
- проверка Python/import/cryptography/AES/secret/local port;
- подробный persistent log в `files/tg-ws-proxy-debug.log`;
- отображение stage и traceback в приложении;
- кнопка «ДИАГНОСТИКА»;
- длинное нажатие на лог копирует лог в буфер обмена;
- сервис не маскирует реальную ошибку сообщением «Прокси остановлен».


## V0.1-debug5 fixes

- Инициализация `errorView` до регистрации STATUS receiver.
- Receiver регистрируется в `onStart()` и снимается в `onStop()`.
- Убран Chaquopy `dict -> java.util.Map`.
- Результаты Python для Kotlin теперь передаются как JSON-строки.


## V0.1-debug6

- Added a generated `tg://proxy` link with the same local host/port and `dd<secret>` format used by the upstream project.
- Added “OPEN TELEGRAM” and “COPY LINK” actions to avoid mistakes when configuring Telegram.
- Listener probe logs now explicitly state that the probe connects and closes without sending MTProto bytes.
- Proxy logs now show `client connected` and the first handshake byte, making it possible to distinguish Telegram traffic from the application's own TCP probe.

### Correct Telegram setup

For manual MTProto configuration in Telegram:
- Server: `127.0.0.1`
- Port: `1443`
- Secret: the 32-character secret shown by the app (without `dd`)

The generated link uses `secret=dd<secret>`; this is the normal link form emitted by tg-ws-proxy. 
