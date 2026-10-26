fn main() {
    println!("cargo:rerun-if-env-changed=VOYAH_CATALOG_URL");
    let catalog = std::env::var("VOYAH_CATALOG_URL")
        .ok()
        .filter(|url| !url.trim().is_empty())
        .unwrap_or_else(|| "https://voyahtune.ru/api/v1/releases".into());
    assert!(!catalog.contains(['\r', '\n']), "Invalid catalog URL");
    println!("cargo:rustc-env=VOYAH_CATALOG_URL={catalog}");
}
