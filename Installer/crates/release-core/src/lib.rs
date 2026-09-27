//! Platform-neutral release models and verification. No ADB, UI, networking or executor.
pub mod catalog;
pub mod compatibility;
pub mod error;
pub mod paths;
pub mod payload;
pub mod recipe;
mod storage;
pub use error::{Error, Result};
