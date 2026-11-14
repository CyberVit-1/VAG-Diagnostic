# Сборка и проверка VAG Diagnostic

Текущая версия0.3.19/code35. Требуются JDK17, Gradle8.11.1, Android SDK
Platform35 и Build Tools35.0.0. Проект использует Gradle Kotlin DSL;
Gradle Wrapper в репозитории отсутствует. SDK задаётся ANDROID_HOME либо
локальным sdk.dir в local.properties; машинные пути не коммитятся.

Из корня проекта с установленным Gradle:

```sh
gradle --no-daemon :app:assembleDebug :app:testDebugUnitTest --stacktrace
python tools/summarize_unit_tests.py
python tools/check_scan_dex_budget.py app/build/outputs/apk/debug/app-debug.apk
```

APK создаётся в app/build/outputs/apk/debug/app-debug.apk, XML результатов —
app/build/test-results/testDebugUnitTest. Проверить failures/errors/skips,
а не только наличие APK. DEX guard контролирует размеры протокольных корутин.

GitHub Actions выполняет сборку, unit-тесты, подсчёт результатов и DEX guard.
Workflow: [.github/workflows/android.yml](.github/workflows/android.yml).
Готовый artifact публикуется только при успехе. За точным source, SHA256 и
результатом текущего APK обращаться к [проверке0.3.19](VERIFICATION_0.3.19.md).
Не считать любой файл с тем же versionName проверенным кандидатом.

Успешные JVM/CI/эмулятор не доказывают чтение автомобиля. Для текущего
кандидата следующий необходимый шаг — один полный опрос и экспорт TXT;
повтор старых версий и отдельные поездки ради каждого исправления не нужны.
Порядок работы и границы подтверждений: [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md).
Версию приложения из-за изменений документации не повышать.
