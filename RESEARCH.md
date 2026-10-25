# VAG Diagnostic — протокольное исследование
Дата проверки общедоступных материалов: 2026-10-21.

## Шкала достоверности
- **A** — спецификация производителя чипа, официальный справочник, опубликованные данные стандарта; применимость ограничена конкретным аппаратным вариантом.
- **B** — открытый исходный код и/или репозиторий с описанием практических испытаний; переносимость не доказана.
- **C** — инженерная гипотеза/платформо-зависимое поведение, требующее трассы автомобиля. В запросы к автомобилю без проверки не включать.

## Найденные алгоритмы и источники
| Алгоритм | Проверенный принцип | Источник и уровень | Оборудование | Основные ограничения | В 0.1.0 |
|---|---|---|---|---|---|
| 1. Идентификация автомобиля | OBD-II Mode 09 PID 02 (VIN) при поддержке конкретного ECU; платформу не угадывать по VIN без справочника | ELM327 datasheet; Ross-Tech Auto-Scan (A) | ELM327 с поддержкой применимого OBD протокола | VIN может отсутствовать, быть недоступен или возвращаться в разных фреймах | Нет |
| 2. Протокол | ELM `ATSP0`, проверка реально полученного `41 00`, запрос `ATDPN` | ELM327 datasheet (A) | ELM327/совместимый | `ATDPN`/строка прошивки не подтверждают проприетарные сервисы | **Да: только OBD-II** |
| 3. Обнаружение блоков | Сбор адресов отдельных ответчиков на `01 00` для legislated OBD; полный VAG список этим НЕ получается | ELM327 datasheet, CAN examples (A) | ISO 15765-4 CAN, ELM | OBD ответчик != произвольный адрес VAG; на старых протоколах физические адреса могут быть не распознаны | **Частично** |
| 4. Gateway Installation List | VCDS получает installed modules через CAN Gateway на совместимых платформах | Ross-Tech AutoScan / Control Module / Installation List (A), vagcan (B) | Проверенный VAG-протокол, правильная адресация, совместимый CAN-интерфейс | DID, формат и транспорт установки зависят от платформы; нет универсальной команды `19` | Нет |
| 5. Подключение к каждому ECU | Адресный маршрут и протокол должны поступать из установленного platform profile; физический CAN ID != логический адрес модуля | Ross-Tech (A), PyVCDS TP2.0 (B) | CAN raw или подходящий OEM VCI | ELM327 часто не предоставляет требуемые TP2.0/временные характеристики | Нет |
| 6. DTC OBD | Сервисы Mode 03/07/0A, позитивные ответы 43/47/4A, декодирование DTC по 2 байтам. CAN-ответ включает число кодов, legacy-ответ без него | SAE J1979 (A), ELM327 datasheet (A) | ELM327 + OBD-II ECU | Нет доступа ко всем VAG блокам; `NO DATA` не доказывает отсутствие кодов | **Да, реализовано в исходниках** |
| 7. Статус DTC | OBD 03 / 07 / 0A — классы ошибки; UDS `19 02 mask` + `59 02` с DTCStatusAvailabilityMask | SAE J1979 и py-uds (A/B) | CAN ISO-TP ECU для UDS | статус битов зависит от `availability mask`, ECU поддерживает только часть | OBD — да, UDS — только чистый парсер |
| 8. VAG DTC расшифровка | Хранить оригинальный 24-битный код, статусы, специфичную идентификацию, использовать лицензионный источник | Ross-Tech wiki (справочная база A для конкретных описаний), vagcan (B) | Не зависит от транспорта | Нельзя превращать 24-битный UDS код в P-код догадкой; CLB/ODX не копировать | Короткий локальный словарь OBD; онлайн источник не интегрирован |
| 9. Нет ответа | Тайм-аут, `NO DATA`, отсутствие последовательных кадров -> partial/no response; не `0 DTC` | ELM327 datasheet ISO-TP chapter (A) | Все интерфейсы | Настройка тайм-аутов индивидуальна; ранний ответ от одного ECU не гарантирует всех остальных | **Да, базовый уровень** |
| 10. Адаптеры | SPP RFCOMM, TCP через конкретную Wi-Fi Network SocketFactory, BLE GATT NUS | Android ConnectivityManager / BluetoothGatt API (A), OBDForge (B) | Отдельные адаптеры для каждого транспорта | BLE UART UUID зависит от устройства, Bluetooth permissions; физическая совместимость не проверена | Драйверы написаны, на автомобиле не проверены |

## Существенные технические факты

1. **VCDS Auto-Scan:** chassis profile задаёт правдоподобные адреса; на CAN-автомобилях с поддержкой Gateway Installation List возможна автоматическая выборка установленных модулей. Это не превращается в универсальный `AT SH` для всех VAG.
2. **CAN/ISO-TP:** в ELM327 `ATH1` показывает заголовки, `CAF1` обычно помогает с ISO-TP/flow control. Возможности контроллера зависят от реализации и прошивки; приём многофреймового сообщения должен проверять размер и sequence numbers.
3. **UDS:** `19 02 FF` — пример чтения DTC by status mask, но применять только к ECU с подтверждённым адресом, транспортом и форматом ответов; в 0.1.0 не отправляется.
4. **VW TP 2.0 / KWP1281:** не подменять OBD-II. PyVCDS прямо исключает ELM327 и использует socketCAN/CANdleLight.
5. **Wi-Fi + интернет:** использовать `Network.getSocketFactory()` для TCP к ELM327 вместо глобального `bindProcessToNetwork`, чтобы не увести поиск описаний в изолированную Wi-Fi сеть адаптера.
6. **Протокол DTC:** у CAN Mode 03 в SAE J1979 — байт количества кодов после 0x43; у старых ISO 9141 / KWP / J1850 ответ может быть `43` + DTC пары без count. Декодирование требует знания определённого ELM протокола.
7. **erWin/ODIS:** доступ к сервисной информации и базам может быть лицензируемым/платным; доступ к публичному порталу не равнозначен праву встраивать базы в мобильное приложение.

## Источники (адреса доступны на 2026-10-21)
- Ross-Tech Auto-Scan: https://www.ross-tech.com/vcds/tour/autoscan.php
- Ross-Tech Control Module: https://www.ross-tech.com/vcds/tour/scm_screen.php
- Ross-Tech Installation List: https://www.ross-tech.com/vcds/tour/installation-list.php
- Ross-Tech Wiki: https://wiki.ross-tech.com/
- ELM327 data sheet: https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DS.pdf
- SAE J1979 (publicly mirrored extract, check publication rights before reuse): https://law.resource.org/pub/us/cfr/ibr/005/sae.j1979.2002.pdf
- SAE J1979 CAN count example: https://old.autonerdz.com/yabbfiles/Attachments/1979v3.pdf
- PyVCDS: https://github.com/baconwaifu/PyVCDS
- kwp-android-logger: https://github.com/bri3d/kwp-android-logger
- vagcan: https://github.com/pashokitsme/vagcan
- ELM327-emulator: https://github.com/Ircama/ELM327-emulator
- OBDForge: https://github.com/edwardlthompson/OBDForge
- Android routing: https://developer.android.com/reference/android/net/ConnectivityManager
- Android network use: https://android-developers.googleblog.com/2016/07/connecting-your-app-to-wi-fi-device.html
- UDS reportDTCByStatusMask: https://uds.readthedocs.io/en/stable/pages/knowledge_base/service.html
- Volkswagen erWin: https://volkswagen.erwin-store.com/



### Дополнительная проверка открытых проектов и адресации — 2026-10-23

**B — общественный проект `mcd-diag-rs`** описывает практический разбор
Gateway node tables и Installation List на проверенных UDS/CAN платформах.
Он использует Panda, J2534, SocketCAN и DoIP, **не ELM327**;
совместимость Android ELM327 этим исследованием не доказана.
Источник: https://github.com/bri3d/mcd-diag-rs

**B с лицензионными оговорками — `vag-uds-ids`** публикует пары
CAN request/response идентификаторов, заявленные как извлечённые из ODIS.
Наличие адреса в таком списке **не доказывает** установку данного блока
на конкретной машине, поддержку через текущий ELM327 или доступность
Gateway Installation List. Никаких произвольных адресов из этого
набора в сканирующий цикл не добавлено. Перед использованием данных
нужно отдельно проверить их происхождение и права на распространение.
Источник: https://github.com/ConnorHowell/vag-uds-ids

**A — UDS DID**: сервис `0x22` запрашивает **конкретный** 16-битный
Data Identifier. Диапазон `F1A0..` не является доказательством
универсального DID списка VAG блоков. Структуру и права доступа задаёт
производитель для данного ECU, поэтому адреса/значения нельзя угадать.
Источник: https://uds.readthedocs.io/en/latest/pages/knowledge_base/did.html

**Результат:** до появления проверенного профиля шлюза и физически
подтверждённого адресного ISO-TP опроса на реальном адаптере
полный VAG Auto-Scan остаётся недоступным.

## Лицензии и безопасность
В данном проекте исходный код написан самостоятельно: чужие исходники и закрытые справочники не включены. Ссылки на GPL-3.0 OBDForge и другие открытые проекты — для анализа концепции, а не для копирования. Нет команд `04`, `14`, `2E`, `27`, `31`, записи/стирания ошибок. Название `VAG Diagnostic` условное рабочее; проект не аффилирован с VAG или Ross-Tech.

## Дополнительное исследование — 23 октября 2026: границы Gateway 19, UDS, ELM327

**A — первичный материал Ross-Tech.** Auto-Scan получает перечень ЭБУ либо из
проверенного профиля шасси, либо через совместимый CAN Gateway Installation List.
Наличие логического адреса **19-CAN Gateway** само по себе не раскрывает его
физический CAN request ID, адрес ответа, формат списка или маршрут.
Некоторые платформы вообще не поддерживают Installation List через эту функцию.
Источники:
- https://www.ross-tech.com/vcds/tour/autoscan.php
- https://www.ross-tech.com/vcds/tour/installation-list.php
- https://www.ross-tech.com/vcds/tour/scm_screen.php

**A — первичный материал Elm Electronics.** ELM327 описывает `AT SH`,
`AT CRA`, CAN-фильтрацию и автоматический ISO-TP Flow Control при `CFC1`.
Эти возможности чипа **не подтверждают** их наличие в клонах,
не доказывают поддержку VW TP 2.0 и не устанавливают адрес конкретного
шлюза. Нельзя отправлять общий `AT SH 7E0` как якобы Gateway 19.
- https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DS.pdf

**A/B — спецификация UDS и её открытая реализация.** В UDS сервис `0x19`
означает `ReadDTCInformation`, запрос `19 02 statusMask`, ответ
`59 02 availabilityMask [24-bit DTC, status byte]...`. Ответ
`7F 19 78` означает `response pending`, а не отсутствие неисправностей.
Это НЕ логический адрес VAG блока 19. Для режима чтения не нужно начинать
с security access, session change или сервиса записи.
- https://github.com/pylessard/python-udsoncan/blob/master/udsoncan/services/ReadDTCInformation.py
- https://uds.readthedocs.io/en/stable/pages/knowledge_base/service.html

**Отложено — необходимо подтвердить, а не угадать:**
1. Адресный профиль конкретной платформы/шлюза.
2. Физический TX/RX CAN и документированный DID/формат Installation List.
3. Реальную поддержку ISO-TP сегментации/Flow Control конкретным ELM327.
4. Чтение/проверку результатов без потери RAW и без неподтверждённых блоков.

**Состояние реализации:** безопасный офлайн-разбор `59 02` и барьер
доступа к будущей адресной диагностике реализованы в 0.1.7;
реального опроса VAG Gateway 19 в этой версии **нет**.
Никакой универсальный `22 DID` из доступных источников
для всей линейки VAG не подтверждён; мы его не выдумываем.

 
## Дополнение 2026-10-23 — адресация ELM327 и DLC в CAN-транскриптах

- **AT SH hhh** выбирает 11-битный CAN ID передающего сообщения;
  **AT CRA hhh** настраивает адрес приёма, ограничивая ответы
  подтверждённым ID. **AT CAF1** означает автоматическое
  ISO-TP форматирование, **AT CFC1** включает автоматическую
  передачу Flow Control в рамках поддержанного протокола.
  Эти команды описаны для фирменного ELM327 и могут работать
  иначе либо отсутствовать в совместимых адаптерах:
  https://www.scandoc.de/en/develop/elm327.html
  https://www.kernel.org/doc/html/latest/networking/device_drivers/can/can327.html
- Встречается форма `ID DLC DATA`, где DLC — однозначно
  декларированная длина CAN кадра. Её нельзя путать с
  ISO-TP PCI при разборе. Источник —
  https://www.kernel.org/doc/html/latest/networking/device_drivers/can/can327.html
- Проект реализует только **локальную подготовку** адресных команд и
  строгий декодер ответа. Версия 0.1.8 не вызывает их по
  физическому ELM без отдельного подтверждённого профиля/возможностей.
- Вероятный маршрут **не равен** установленному Gateway 19:
  физический CAN ID из чужой платформы и OBD адрес `7E8`
  не предоставляют права делать OEM-опрос на этом автомобиле.

## Безопасный следующий аппаратный шаг — 2026-10-23

- ELM327 официально поддерживает 11-битные CAN-заголовки `ATSH`,
  фильтры приёма `ATCRA` и автоматический ISO-TP Flow Control
  (`ATCFC1`), но у клонов отдельные команды могут не работать,
  даже если отвечают `OK`. Источник:
  https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DS.pdf
- Только **законодательно стандартизированный** OBD-II Mode09 PID00
  (`0900`) и PID02 (`0902` VIN) используются для первой проверки
  сегментированных ответов. Для 11-bit CAN OBD physical запросы
  `7E0..7E7` соответствуют ответа `7E8..7EF`. VIN не
  используется для догадок о VAG-платформе и не отображается в UI.
- Успешный адресный `0902` доказывает работу этой пары на
  данном транспорте/адаптере, но **не** доказывает доступность Gateway
  19, произвольного UDS сервиса `19` или VW TP2.0.
  До получения реального журнала об этих функциях выводов нет.


## TP2.0 для некоторых платформ VAG — протокольный этап 0.2.2

- SAE International J2819_201905: VW TP 2.0 — отдельный
  диагностический транспорт; требует специфического протокольного
  обмена, не эквивалентен UDS/ISO-TP:
  https://saemobilus.sae.org/standards/j2819_201905-tp20-vehicle-diagnostic-protocol
- I CAN Hack, описание TP2.0 (по материалам реверс-инжиниринга):
  `0x200` — setup request, ответ `0x200 + logical ECU address`.
  Пример данных `09 C0 00 10 00 03 01` к адресу `0x09`;
  для Gateway `0x19` используется только адресный setup,
  без KWP сервисов.
  https://icanhack.nl/knowledge-base/diagnostics/vw-tp20/
- Ross-Tech Auto-Scan документация по шасси Golf Mk5/1K и
  Gateway Installation List: 
  https://www.ross-tech.com/vcds/tour/autoscan.php
- Тестовый профиль ограничен Volkswagen VIN/1K; это
  **подозрение на применимость**, не гарантированная поддержка
  TP2.0 Gateway конкретным автомобилем или клоном ELM.
- Даже положительный setup D0 будет доказывать только начальное
  согласование канала. Нужна отдельная реализация KWP2000 TP2.0,
  ACK, таймингов и корректного списка ЭБУ, прежде чем говорить
  о полноценном VAG Auto-Scan.

## Реальный ответ TP2.0 25 октября 2026 и настройки ELM

- По журналу 0.2.2 семибайтовый `CHANNEL SETUP` TP2.0 после `ATCAF0` мог передаваться с CAN DLC8. ELM327 поддерживает `ATV1` — разрешить Variable DLC, используемый в изолированном канале; это возможное объяснение `NO DATA`, пока не доказанная причина.
- `ATST96` = 0x96 × 4 мс = 600 мс, `ATAT0` выключает адаптивное сокращение; прошлый `NO DATA` через ~278 мс не подтверждает отсутствие шлюза.
- Первичный справочник ELM327: https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DS.pdf; протоколы 0–C также приведены в https://www.scandoc.org/en/develop/elm327.html.
