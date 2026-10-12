# DiPlay внутри VoyahTune

`common` и `shared` включены как библиотеки в APK RestoreMode. Отдельный APK
DiPlay не собирается; штатные экраны доступны через Activity и виджеты VoyahTune.

[Полная спецификация интеграции и восстановления после замены upstream](../../Docs/carplay-diplay.md)
— основной документ сопровождения: контракты, карта изменённых файлов, автомобильный IPC, UI, аудио, authentication assets и порядок восстановления.

[Правила работы с этой копией DiPlay](AGENTS.md).

Исходные [LICENSE](LICENSE) и [THIRD_PARTY_NOTICES](docs/THIRD_PARTY_NOTICES.md)
сохранены. Runtime identity передаётся сборке через `DIPLAY_AUTH_ASSETS_DIR`
из локального `Credentials/CarPlayKeys` в корне проекта (внутри — `offline-mfi/`).
Каталог `Credentials/` исключён из Git.
