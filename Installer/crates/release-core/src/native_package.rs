const APPLICATION_DATA: &str =
    include_str!("../../../../Packaging/installer/common/application-data.sh");
const PREPARE: &str =
    include_str!("../../../../Packaging/installer/common/prepare-native-system-package.sh");

pub const VERIFY: &str =
    include_str!("../../../../Packaging/installer/common/verify-native-system-package.sh");

pub fn prepare_script() -> String {
    format!(
        "backup_native_data() (\napplication_data_action=backup\napplication_package=ru.big.town.anative\n{APPLICATION_DATA}\n)\n{PREPARE}"
    )
}
