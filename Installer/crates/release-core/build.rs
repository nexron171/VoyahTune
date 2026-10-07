fn main() {
    println!("cargo:rerun-if-env-changed=VOYAH_INFRASTRUCTURE");
    println!("cargo:rerun-if-env-changed=VOYAH_CATALOG_URL");
    let infrastructure = std::env::var("VOYAH_INFRASTRUCTURE").unwrap_or_else(|_| "od".into());
    assert!(
        matches!(infrastructure.as_str(), "pi" | "od"),
        "VOYAH_INFRASTRUCTURE must be pi or od"
    );
    let catalog = std::env::var("VOYAH_CATALOG_URL")
        .ok()
        .filter(|url| !url.trim().is_empty())
        .unwrap_or_else(|| {
        if infrastructure == "od" {
            "https://gitlab.com/openvoyah/voyahtune/-/raw/master/Releases/ota/index-v4.json".into()
        } else {
            "https://raw.githubusercontent.com/nexron171/VoyahTune/master-od/Releases/ota/index.json".into()
        }
    });
    assert!(!catalog.contains(['\r', '\n']), "Invalid catalog URL");
    println!("cargo:rustc-env=VOYAH_INFRASTRUCTURE={infrastructure}");
    println!("cargo:rustc-env=VOYAH_CATALOG_URL={catalog}");
}
