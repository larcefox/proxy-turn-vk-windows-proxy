> [!CAUTION]
> ## Исходный проект архивирован
> Основная разработка и поддержка WDTT прекращены. Этот форк содержит пользовательские изменения и автоматизированную сборку APK, но предоставляется без гарантий и технической поддержки.

<div align="center">

# WDTT — WireGuard over TURN Tunnel

<br>

<img src="https://img.shields.io/badge/Android-SDK_29--35-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android SDK">
<img src="https://img.shields.io/badge/Go-1.26-00ADD8?style=for-the-badge&logo=go&logoColor=white" alt="Go Version">
<img src="https://img.shields.io/badge/Kotlin-Compose-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin">
<a href="https://github.com/amurcanov/proxy-turn-vk-android/stargazers">
  <img src="https://img.shields.io/github/stars/amurcanov/proxy-turn-vk-android?style=for-the-badge&logo=github&color=ffca28&labelColor=24292e" alt="Stars">
</a>

[![Build APK](https://github.com/larcefox/proxy-turn-vk-windows-proxy/actions/workflows/build-apk.yml/badge.svg)](https://github.com/larcefox/proxy-turn-vk-windows-proxy/actions/workflows/build-apk.yml)

[WDTT Telegram Community](https://t.me/wdttcommunity)

</div>

<br>

**WDTT** — это Android-приложение для создания защищённого **WireGuard-туннеля поверх TURN/DTLS**. Клиент поднимает локальный VPN-интерфейс на устройстве, получает WireGuard-конфигурацию от вашего VPS и передаёт транспорт через TURN-серверы VK, маскируя соединение под обычный зашифрованный медиатрафик звонка.

## Изменения этого форка

- Включаемый «Доступ к локальной сети» на вкладке «Исключения».
- При включённой опции адреса локальной сети идут мимо VPN, а интернет-трафик остаётся в туннеле WDTT. Это позволяет подключаться к Every Proxy с других устройств в LAN.
- Настройка хранится отдельно для каждого профиля.
- GitHub Actions автоматически собирает APK после каждого push в `main`.

## Скачать APK

Откройте [Build APK](https://github.com/larcefox/proxy-turn-vk-windows-proxy/actions/workflows/build-apk.yml), выберите последнюю успешную сборку и скачайте артефакт `WDTT-APKs`. Если архитектура устройства неизвестна, используйте `WDTT-universal.apk`.

APK из CI подписаны debug-ключом. Для стабильных обновлений поверх уже установленной версии нужно подключить постоянный release-ключ через GitHub Secrets.

> [!WARNING]
> ## Назначение проекта и дисклеймер
> Приложение является техническим инструментом для защищённого туннелирования трафика через ваш собственный TURN-сервер. Проект WDTT носит исключительно витринно-ознакомительный характер на примере уже имеющейся структуры в целях исследования сетевых протоколов.
>
> Я (**amurcanov**) не призываю использовать WDTT для обхода блокировок или нарушения правил каких-либо платформ, а также не несу ответственности за финальные сценарии использования утилиты пользователями в реальной жизни или сети ИНТЕРНЕТ. Любые специфические технические особенности приложения — не более чем архитектурное совпадение, созданное без какого-либо умысла.
>
> Проект является полностью некоммерческим, не содержит платных функций, скрытых подписок или коммерческой выгоды.

## Лицензия

Этот проект распространяется под лицензией **GNU General Public License v3.0**.
