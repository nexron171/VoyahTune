with_writable_system() (
    mount_info=$(awk -v directory="${startup%/*}" '
        ($2 == "/" || directory == $2 || index(directory, $2 "/") == 1) && length($2) >= longest {
            target = $2
            options = $4
            longest = length($2)
        }
        END { if (longest) print target, options }
    ' /proc/mounts)
    [ -n "$mount_info" ] || fail 'System mount unavailable'
    target=${mount_info%% *}
    options=${mount_info#* }
    probe=

    # Keep the writable mount for the following installation operation.
    trap '
        status=$?
        [ -z "$probe" ] || rm -f "$probe" || status=1
        exit "$status"
    ' EXIT

    case ",$options," in
        *,ro,*)
            mount -o remount,rw "$target" || fail 'Read-only file system: system remount failed'
            ;;
        *,rw,*) ;;
        *) fail 'Unknown system mount mode' ;;
    esac

    probe=$(mktemp "$startup.voyahtune-write-test.XXXXXX")
    printf '%s\n' 'voyahtune' > "$probe"
    rm "$probe"
    probe=
    "$@"
)
