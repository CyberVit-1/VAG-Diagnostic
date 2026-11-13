# Проверенная сборка0.3.17/code33 —13.11.2026

## Точный исходник и файл

Source6f78046c3a4c3f24eb633963e171ea4c98d413bf,
tree e77b224dccc44ba6a2f2fd7644cbe7cf4d205371.
PR mergeafe060ce2978cace73154877c6f185d28d5577b1 имеет тот же tree.
Ветка feature/initial-android-build; PR#1 остаётся draft, main не изменён.

APK VAG-Diagnostic-v0.3.17.apk:18708097bytes,
SHA25676d531d6b0c2275ddeba319641ee2e2d3db847bd14d71f6eb422071bf5778084.
Выбран push artifact14021969027. Его ZIP18144936bytes,
SHA256c780f7e5c4968cc5edd3b8e02ad42570141e4f27a43bda02d1da7ddc7e7d4530
совпал с GitHub metadata. APK извлечён без изменения; ZIP CRC проверен.
Пакет com.vagdiagnostic.app, versionName0.3.17/versionCode33, minSdk26,
Roomv1 прежние. VIN и приватные материалы в содержимом APK не найдены.

## Compile-checked / Software-tested

- Целевой профиль36 PASS: четыре CAN backend, listed/candidate/supplemental
  границы, собственный DTC, тип компонента и его отсутствие, плохие данные,
  IO/отмена, новый проверенный stream без повтора DTC, исторический replay.
- Полный локальный JVM:532 tests/58 suites,0 failures/errors/skipped,6m09s.
- Полный локальный Android assemble/test:532/58,0 failures/errors/skipped,8m06s.
- [Push CI](https://github.com/CyberVit-1/VAG-Diagnostic/actions/runs/43393563822), job131778999346: assemble/test PASS,532/58,0 failures/errors/skipped.
- [PR CI](https://github.com/CyberVit-1/VAG-Diagnostic/actions/runs/43393571807), job131779024268: assemble/test PASS,532/58,0 failures/errors/skipped.
- APK artifacts14021969027(push) и14022092671(PR) существуют, не просрочены.
- DEX guard локально, в обоих CI и на выданном APK:858 methods/max112
  registers/11564 code units, oversized=[].
- Аудит на этапе проверки APK:25 журналов,194 tracked files/956 history
  blobs; действительный VIN в APK/Git не найден. RAW хранится вне репозитория.

Промежуточные отрицательные результаты и адаптация только синтетических
тихих preflight отражены в [кандидате](CANDIDATE_0.3.17.md); исходные TSV
не менялись. Успех итоговых прогонов не выдаётся за успех ранних отказов.

## Vehicle-confirmed / следующий необходимый тест

Реально испытана0.3.16:16 полных собственных чтений вместо15,72 прочитан,
3 записи DTC;04/46 без отдельного ответа, диапазон журнала106.228s.
[Разбор журнала](VEHICLE_TEST_2026-11-13.md). Новые форматы Gateway и
дополнительные UDS-статусы этим журналом полностью не подтверждены.

0.3.17 физически ещё не проверена. Сокращение запросов доказано программно,
ускорение/стабильность на автомобиле не обещаются. Связь46 с09 требует
недостающего типа компонента; чужие DTC не копируются,04 не назван виртуальным.

Теперь нужен один обычный полный опрос0.3.17 и TXT в чат. Проверяются все
16 прежних чтений/3 записи,72, состояние04/46, подтверждение типа09 и время.
Отдельные поездки/запуски для каждого изменения не нужны. Порядок чистой
установки уже согласован; изменений пакета/хранения нет. После TXT анализ и
исправления продолжаются автономно до следующего необходимого испытания.
