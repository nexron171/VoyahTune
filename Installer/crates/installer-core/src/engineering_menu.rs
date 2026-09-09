use crate::{Error, Result};
use chrono::{Datelike, FixedOffset, NaiveDate, Utc};
use serde::Serialize;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EngineeringCode {
    pub date: String,
    pub code: String,
    pub time_zone: &'static str,
    pub approximate: bool,
}
pub fn calculate(date: Option<&str>) -> Result<EngineeringCode> {
    let date = match date {
        Some(value) => NaiveDate::parse_from_str(value, "%Y-%m-%d").map_err(|e| {
            Error::new("INVALID_DATE", "Укажите существующую дату ГГГГ-ММ-ДД").detail(e)
        })?,
        None => Utc::now()
            .with_timezone(&FixedOffset::east_opt(8 * 3600).unwrap())
            .date_naive(),
    };
    if !(1..=9999).contains(&date.year()) {
        return Err(Error::new("INVALID_DATE", "Год должен быть от 1 до 9999"));
    }
    let year = format!("{:04}", date.year());
    let month_day = format!("{:02}{:02}", date.month(), date.day());
    let code = year
        .bytes()
        .zip(month_day.bytes())
        .map(|(a, b)| ((a - b'0') + (b - b'0')).to_string())
        .collect();
    Ok(EngineeringCode {
        date: date.to_string(),
        code,
        time_zone: "Asia/Shanghai",
        approximate: true,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn matches_android() {
        for (d, c) in [
            ("2026-07-28", "27414"),
            ("2026-01-01", "2127"),
            ("2024-02-29", "22413"),
            ("2026-12-31", "3257"),
        ] {
            assert_eq!(calculate(Some(d)).unwrap().code, c);
        }
        assert!(calculate(Some("2026-02-29")).is_err());
        assert!(calculate(Some("0000-01-01")).is_err());
    }
}
