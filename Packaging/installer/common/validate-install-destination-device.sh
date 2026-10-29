reject_install_symlinks() {
    voyah_install_path=$1
    case "$voyah_install_path" in /*) ;; *) return 1 ;; esac
    while :; do
        [ ! -L "$voyah_install_path" ] || {
            printf 'Symlink in install destination: %s\n' "$voyah_install_path" >&2
            return 1
        }
        [ "$voyah_install_path" != / ] || break
        voyah_install_path=${voyah_install_path%/*}
        [ -n "$voyah_install_path" ] || voyah_install_path=/
    done
}
