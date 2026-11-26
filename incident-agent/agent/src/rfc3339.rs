fn number(text: &str, range: std::ops::RangeInclusive<i64>) -> Option<i64> {
    if text.is_empty() || !text.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    text.parse().ok().filter(|value| range.contains(value))
}

fn days_from_civil(year: i64, month: i64, day: i64) -> i64 {
    let year = if month <= 2 { year - 1 } else { year };
    let era = year.div_euclid(400);
    let year_of_era = year - era * 400;
    let shifted_month = (month + 9) % 12;
    let day_of_year = (153 * shifted_month + 2) / 5 + day - 1;
    let day_of_era = year_of_era * 365 + year_of_era / 4 - year_of_era / 100 + day_of_year;
    era * 146_097 + day_of_era - 719_468
}

fn offset_secs(zone: &str) -> Option<i64> {
    if zone == "Z" {
        return Some(0);
    }
    let sign = match zone.as_bytes().first()? {
        b'+' => 1,
        b'-' => -1,
        _ => return None,
    };
    let (hours, minutes) = zone.get(1..)?.split_once(':')?;
    Some(sign * (number(hours, 0..=23)? * 3600 + number(minutes, 0..=59)? * 60))
}

pub(crate) fn parse_unix(text: &str) -> Option<u64> {
    let (date, time) = text.split_once('T')?;
    let zone_at = time.find(['Z', '+', '-'])?;
    let (clock, zone) = time.split_at(zone_at);
    let clock = clock.split_once('.').map_or(clock, |(whole, _)| whole);
    let mut date_parts = date.split('-');
    let year = number(date_parts.next()?, 1970..=9999)?;
    let month = number(date_parts.next()?, 1..=12)?;
    let day = number(date_parts.next()?, 1..=31)?;
    let mut clock_parts = clock.split(':');
    let hour = number(clock_parts.next()?, 0..=23)?;
    let minute = number(clock_parts.next()?, 0..=59)?;
    let second = number(clock_parts.next()?, 0..=60)?;
    if date_parts.next().is_some() || clock_parts.next().is_some() {
        return None;
    }
    let days = days_from_civil(year, month, day);
    let local = days * 86_400 + hour * 3600 + minute * 60 + second;
    u64::try_from(local - offset_secs(zone)?).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rfc3339_z는_unix로_바뀐다() {
        assert_eq!(parse_unix("2025-11-26T00:00:00Z"), Some(1_764_115_200));
    }

    #[test]
    fn 소수점_초와_오프셋을_받는다() {
        assert_eq!(
            parse_unix("2025-11-26T09:00:00.123456789+09:00"),
            Some(1_764_115_200)
        );
        assert_eq!(parse_unix("2025-11-25T19:00:00-05:00"), Some(1_764_115_200));
    }

    #[test]
    fn 형식이_틀리면_none이다() {
        assert_eq!(parse_unix("2025-11-26"), None);
        assert_eq!(parse_unix("2025-13-26T00:00:00Z"), None);
        assert_eq!(parse_unix("garbage"), None);
        assert_eq!(parse_unix("1969-12-31T23:59:59Z"), None);
    }
}
