# GitHub Android CI — VAG Diagnostic

**Версия исходного проекта:** 0.1.0  
**Дата:** 2026-10-21  
**Ветка разработки:** `feature/initial-android-build`  
**Pull Request:** https://github.com/CyberVit-1/VAG-Diagnostic/pull/1

## Исходники
Все **19 файлов** исходного архива `VagDiagnostic_v0.1.0_SOURCE.zip` импортированы в корень репозитория коммитом `43138e891c1053deb9e23f381dad92fbabe5c429`. Ветку `main` не изменяли.

## Автоматическая сборка
**Результат:** УСПЕШНО. [Запуск GitHub Actions №39628972361](https://github.com/CyberVit-1/VAG-Diagnostic/actions/runs/39628972361) завершён с `success`. `assembleDebug` и `testDebugUnitTest` прошли, артефакт `VAG-Diagnostic-v0.1.0-debug-apk` сохранён. Размер ZIP-артефакта: 17 712 022 байта.
Workflow: `.github/workflows/android.yml` — Java 17, Android SDK 35, Gradle 8.11.1.

1. Откройте [GitHub Actions](https://github.com/CyberVit-1/VAG-Diagnostic/actions).
2. Найдите последний запуск `Android APK (debug)` в ветке `feature/initial-android-build`.
3. Только **после успеха** скачайте artifact `VAG-Diagnostic-v0.1.0-debug-apk` из запуска.
4. При `failure` проверяйте шаг `Compile debug APK` и технический журнал; отсутствие `artifact` означает отсутствие подтверждённой сборки.

Исходный ZIP не содержит `gradle-wrapper.jar`: в workflow применяется установленный Gradle 8.11.1. На ранних запусках возникла инфраструктурная ошибка установки `tools` и поиска `sdkmanager`; обе причины были исправлены в конфигурации. Успешная сборка подтверждена проверкой результата выполнения workflow. Проверка физического автомобиля не проводилась.

## Ограничения
- APK успешно собран и сохранён в GitHub Actions; установка на физический Android-смартфон и испытания **пока не подтверждены**.
- Полный VAG Auto-Scan и чтение CAN Gateway Installation List **не реализованы**.
- Транспорты Wi-Fi TCP, Bluetooth SPP и BLE NUS представлены в исходниках, но пока не проверены на реальном автомобиле.

## Действия пользователя
**Для следующей аппаратной проверки:** скачать ZIP с APK из успешного запуска GitHub Actions, распаковать, установить APK на смартфон, испытать с физическим Wi-Fi ELM327 и предоставить экспорт диагностического журнала.

## Далее
Подтвердить установку собранного APK и подключение физического Wi-Fi ELM327, затем чтение реальных DTC. Отдельно проверить Bluetooth SPP и BLE NUS, не объявляя их работоспособными до аппаратных испытаний.
