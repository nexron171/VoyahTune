//! Platform-neutral release models and verification. No ADB, UI, networking or executor.
pub mod catalog;
pub mod compatibility;
pub mod error;
pub mod infrastructure;
pub mod paths;
pub mod payload;
pub mod recipe;
pub use error::{Error, Result};
pub mod apk_identity;
pub mod ota;

pub mod installation_logcat;
pub mod vehicle_model;

pub mod runtime_maintenance;
pub mod runtime_readiness;
