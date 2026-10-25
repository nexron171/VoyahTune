//! One model policy for USB and OTA package selection.

pub const PROPERTIES_COMMAND: &str = "set -e\ngetprop ro.build.fingerprint\ngetprop ro.build.description\ngetprop ro.product.model\ngetprop persist.qg.canbus.ipk_software\ngetprop persist.tracking.frombrand\n";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VehicleModel {
    H97X,
    H97Y,
    Other,
}

impl VehicleModel {
    pub fn detect(properties: &str) -> Self {
        let properties = properties.to_ascii_uppercase();
        // Generic H97C branding can coexist with a concrete H97X/Y build identity.
        if properties.contains("H97X") {
            Self::H97X
        } else if properties.contains("H97Y") {
            Self::H97Y
        } else {
            Self::Other
        }
    }

    pub fn needs_runyn(self) -> bool {
        self == Self::Other
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn runyn_is_excluded_on_h97xy_even_with_generic_h97c_branding() {
        for properties in [
            "jenkins-PRJ_H97x_Platform_8155-650\nH97C",
            "msmnile_gvmq\nH97XSA8155-DAILY-20251010221730-USR",
            "RQ3A.211001.001 H97Y\nH97C",
        ] {
            assert!(
                !VehicleModel::detect(properties).needs_runyn(),
                "{properties}"
            );
        }
        for properties in ["H97C", "H97A", "free-11-chinese-20260731-7.3.1"] {
            assert!(VehicleModel::detect(properties).needs_runyn());
        }
    }
}
