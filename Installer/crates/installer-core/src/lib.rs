pub mod adb;
pub mod canbus;
pub mod catalog;
mod classic_commands;
pub mod compatibility;
pub mod engine;
pub mod engineering_menu;
pub mod error;
pub mod events;
pub mod inventory;
pub mod mode;
pub mod payload;
pub mod plans;
pub mod recipe;
pub mod recovery;
pub mod session;

pub use error::{Error, Result};
pub const PROTOCOL_VERSION: u32 = 1;
