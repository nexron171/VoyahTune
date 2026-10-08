# DiPlay внутри VoyahTune

`common` и `shared` включены как библиотеки в APK RestoreMode. Отдельный APK
DiPlay не собирается; штатные экраны доступны через Activity и виджеты VoyahTune.

[Архитектура, локальные изменения, authentication assets и порядок обновления](../../Docs/carplay-diplay.md)
— основной документ сопровождения с зафиксированными версией и commit upstream.

Исходные [LICENSE](LICENSE) и [THIRD_PARTY_NOTICES](docs/THIRD_PARTY_NOTICES.md)
сохранены. Runtime identity передаётся сборке через `DIPLAY_AUTH_ASSETS_DIR`
и не хранится в исходниках.
