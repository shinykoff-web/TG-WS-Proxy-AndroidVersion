# TG WS Proxy Android V0.2 UI

Рабочая Android-обвязка над Python-ядром TG WS Proxy 1.10.4 с упрощённым интерфейсом.

## Главное

- Чистый основной экран без debug-лога.
- Большая кнопка запуска/остановки прокси.
- Статус локального listener `127.0.0.1:1443`.
- Кнопка открытия готовой `tg://proxy` ссылки в Telegram.
- Кнопка копирования ссылки.
- Secret отображается отдельно и копируется долгим нажатием.
- Foreground Service сохраняет прокси после ухода с экрана.

## Настройки / диагностика

Кнопка с шестерёнкой открывает отдельный экран `Настройки`:

- запуск preflight-диагностики Python/import/cryptography/AES/secret/local port;
- запуск диагностики даже при уже работающем прокси;
- persistent debug log;
- обновление, копирование и очистка лога;
- ошибки диагностики показываются отдельно от главного экрана.

## Сетевое ядро

Каталог `app/src/main/python/proxy/` оставлен на базе исходного TG WS Proxy 1.10.4.
Android-слой состоит из `MainActivity.kt`, `ProxyService.kt`, `DiagnosticsActivity.kt` и `android_proxy.py`.

## Сборка

Откройте проект в Android Studio. Нужен Android SDK и JDK 17.
Chaquopy 17 использует Python 3.13.

## Telegram

Для ручной настройки:

- Server: `127.0.0.1`
- Port: `1443`
- Secret: 32-символьный secret из приложения, без `dd`

Для автоматического перехода используется ссылка вида:

`tg://proxy?server=127.0.0.1&port=1443&secret=dd<secret>`
