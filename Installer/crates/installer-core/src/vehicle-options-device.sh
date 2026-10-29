set -eu

fail() {
    echo "$1" >&2
    exit 1
}

require_backup() {
    [ ! -L "$backup" ] && [ -d "$backup" ] && [ -f "$backup/.complete" ] ||
        fail "Резервная копия $backup не подтверждена. Сохраните её отдельно и освободите путь перед повторной установкой."
}

mount -o rw,remount /

case "$action" in
    fake-account)
        account=/private/configs/token/accountInfo
        parent=/data/local/voyahtune-backups
        backup=$parent/fake-account
        staging=$parent/.fake-account.new
        [ -d /private/configs/token ] || fail 'Нет каталога OEM аккаунта /private/configs/token'
        [ ! -L "$parent" ] || fail "Недопустимый путь резервных копий $parent"
        mkdir -p "$parent"
        chown 0:0 "$parent"
        chmod 700 "$parent"
        if [ -e "$backup" ] || [ -L "$backup" ]; then
            require_backup
            [ -f "$backup/accountInfo" ] || [ -f "$backup/accountInfo.absent" ] ||
                fail 'В резервной копии отсутствует исходное состояние аккаунта'
        else
            [ ! -L "$staging" ] || fail "Недопустимый путь $staging"
            rm -rf "$staging"
            mkdir "$staging"
            chmod 700 "$staging"
            if [ -f "$account" ]; then
                cp "$account" "$staging/accountInfo"
                chown 0:0 "$staging/accountInfo"
                chmod 600 "$staging/accountInfo"
                cmp "$account" "$staging/accountInfo" || fail 'Копия аккаунта отличается от исходного файла'
            elif [ -e "$account" ] || [ -L "$account" ]; then
                fail 'accountInfo не является обычным файлом'
            else
                : > "$staging/accountInfo.absent"
            fi
            : > "$staging/.complete"
            sync
            mv "$staging" "$backup"
            sync
        fi
        temporary=/private/configs/token/.accountInfo.voyahtune-new
        [ ! -L "$temporary" ] || fail "Недопустимый путь $temporary"
        printf '%s\n' 'Owner%+79991234567%1%100001%100001' > "$temporary"
        chown 0:0 "$temporary"
        chmod 444 "$temporary"
        mv -f "$temporary" "$account"
        mkdir -p /tmp
        : > /fake_login
        : > /tmp/fake_login
        sync
        echo "Fake аккаунт активирован. Исходное состояние сохранено на автомобиле: $backup"
        ;;
    disable-navigation)
        source=/system/priv-app/dueros
        backup=/sdcard/dueros
        staging=/sdcard/.dueros.voyahtune-new
        if [ ! -e "$source" ] && [ ! -L "$source" ]; then
            echo 'Китайская навигация dueros уже отсутствует'
            exit 0
        fi
        [ ! -L "$source" ] && [ -d "$source" ] || fail 'dueros не является обычным каталогом'
        [ -d /sdcard ] || fail 'Хранилище /sdcard недоступно для резервной копии'
        if [ -e "$backup" ] || [ -L "$backup" ]; then
            require_backup
        else
            [ ! -L "$staging" ] || fail "Недопустимый путь $staging"
            rm -rf "$staging"
            cp -R "$source" "$staging"
            : > "$staging/.complete"
            sync
            mv "$staging" "$backup"
            sync
        fi
        rm -rf "$source"
        sync
        [ ! -e "$source" ] || fail 'Не удалось удалить китайскую навигацию dueros'
        echo "Китайская навигация отключена. Резервная копия на автомобиле: $backup"
        ;;
    *) fail "Неизвестная настройка автомобиля: $action" ;;
esac
