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

## Лицензии и безопасность
В данном проекте исходный код написан самостоятельно: чужие исходники и закрытые справочники не включены. Ссылки на GPL-3.0 OBDForge и другие открытые проекты — для анализа концепции, а не для копирования. Нет команд `04`, `14`, `2E`, `27`, `31`, записи/стирания ошибок. Название `VAG Diagnostic` условное рабочее; проект не аффилирован с VAG или Ross-Tech.