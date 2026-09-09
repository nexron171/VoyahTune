use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Error {
    pub code: String,
    pub message: String,
    pub detail: String,
    pub next_action: String,
    pub retryable: bool,
}
pub type Result<T> = std::result::Result<T, Error>;
impl Error {
    pub fn new(code: &str, message: impl Into<String>) -> Self {
        Self {
            code: code.into(),
            message: message.into(),
            detail: String::new(),
            next_action: "Сохраните отчёт и устраните указанную причину.".into(),
            retryable: false,
        }
    }
    pub fn detail(mut self, detail: impl ToString) -> Self {
        self.detail = detail.to_string();
        self
    }
    pub fn retry(mut self, action: &str) -> Self {
        self.retryable = true;
        self.next_action = action.into();
        self
    }
}
impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}: {}\n{}", self.code, self.message, self.detail)
    }
}
impl std::error::Error for Error {}
impl From<std::io::Error> for Error {
    fn from(e: std::io::Error) -> Self {
        Self::new("LOCAL_IO", "Ошибка доступа к локальным файлам или процессу").detail(e)
    }
}
impl From<serde_json::Error> for Error {
    fn from(e: serde_json::Error) -> Self {
        Self::new("INVALID_JSON", "Некорректный формат данных").detail(e)
    }
}
