// Salary periods on UTC boundaries, integers only.
//
// period_key (u32) = period << 28 | index, so a key names its own kind of period and a day can
// never be mistaken for a month after the salary changes its period:
//   day   (1): index = days since 1970-01-01;
//   week  (2): index = weeks since Monday 1969-12-29 (weeks start on Monday, 00:00 UTC);
//   month (3): index = (year - 1970) * 12 + (month - 1).

pub const PERIOD_DAY: u8 = 1;
pub const PERIOD_WEEK: u8 = 2;
pub const PERIOD_MONTH: u8 = 3;
pub const DAY_MS: u64 = 86_400_000;
pub const DUE_BACK: u64 = 12;
const INDEX_BITS: u32 = 28;
const INDEX_LIMIT: u64 = 1 << INDEX_BITS;

// Howard Hinnant's civil_from_days, shifted so every operand stays unsigned (days >= 0).
pub fn civil_from_days(days: u64) -> (u64, u64) {
    let z = days + 719_468;
    let era = z / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + u64::from(month <= 2);
    (year, month)
}

pub fn period_index(period: u8, ms: u64) -> Option<u64> {
    let days = ms / DAY_MS;
    match period {
        PERIOD_DAY => Some(days),
        // 1970-01-01 was a Thursday: three days earlier is the Monday that opens week 0.
        PERIOD_WEEK => Some((days + 3) / 7),
        PERIOD_MONTH => {
            let (year, month) = civil_from_days(days);
            Some(year.checked_sub(1970)? * 12 + (month - 1))
        },
        _ => None,
    }
}

pub fn key_of(period: u8, index: u64) -> Option<u32> {
    if !matches!(period, PERIOD_DAY | PERIOD_WEEK | PERIOD_MONTH) || index >= INDEX_LIMIT {
        return None;
    }
    u32::try_from((u64::from(period) << INDEX_BITS) | index).ok()
}

pub fn period_key(period: u8, ms: u64) -> Option<u32> {
    key_of(period, period_index(period, ms)?)
}

pub fn key_valid(key: u32) -> bool {
    matches!(
        u8::try_from(key >> INDEX_BITS),
        Ok(PERIOD_DAY | PERIOD_WEEK | PERIOD_MONTH)
    )
}

// Finished periods since from_ms (the period holding from_ms counts: the first day is worked
// from the moment the salary starts), never more than DUE_BACK of them.
pub fn finished_keys(period: u8, from_ms: u64, now_ms: u64) -> Vec<u32> {
    owed_keys(period, from_ms, None, now_ms)
}

// The finished periods one salary's terms owe: from the period holding from_ms up to, not including, the period holding
// until_ms -- the moment a later salary took over, whose period is the later one's -- and never past the current period
// nor more than DUE_BACK back. A later salary starting before this one's own start leaves it nothing.
pub fn owed_keys(period: u8, from_ms: u64, until_ms: Option<u64>, now_ms: u64) -> Vec<u32> {
    let (Some(first), Some(current)) =
        (period_index(period, from_ms), period_index(period, now_ms))
    else {
        return Vec::new();
    };
    let end = until_ms
        .and_then(|t| period_index(period, t))
        .map_or(current, |i| i.min(current));
    let lo = first.max(current.saturating_sub(DUE_BACK));
    (lo..end).filter_map(|i| key_of(period, i)).collect()
}

// Howard Hinnant's days_from_civil for the first day of a month, every operand unsigned (year 1970 and later).
pub fn days_from_civil(year: u64, month: u64) -> u64 {
    let y = if month <= 2 { year - 1 } else { year };
    let era = y / 400;
    let yoe = y - era * 400;
    let mp = if month > 2 { month - 3 } else { month + 9 };
    let doy = (153 * mp + 2) / 5;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

fn period_start_days(period: u8, index: u64) -> Option<u64> {
    match period {
        PERIOD_DAY => Some(index),
        // Week 0 opened on Monday 1969-12-29, three days before the epoch: it is counted from the epoch.
        PERIOD_WEEK => Some((index.checked_mul(7)?).saturating_sub(3)),
        PERIOD_MONTH => Some(days_from_civil(1970 + index / 12, index % 12 + 1)),
        _ => None,
    }
}

// The moments a period key spans, [from_ms, to_ms) on UTC boundaries: what a screen names a due period by.
pub fn bounds(key: u32) -> Option<(u64, u64)> {
    let period = u8::try_from(key >> INDEX_BITS).ok()?;
    let index = u64::from(key) & (INDEX_LIMIT - 1);
    let from = period_start_days(period, index)?;
    let to = period_start_days(period, index + 1)?;
    Some((from.checked_mul(DAY_MS)?, to.checked_mul(DAY_MS)?))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn civil_dates() {
        assert_eq!(civil_from_days(0), (1970, 1));
        assert_eq!(civil_from_days(30), (1970, 1));
        assert_eq!(civil_from_days(31), (1970, 2));
        assert_eq!(civil_from_days(59), (1970, 3));
        // 2000-02-29 is day 11016, 2000-03-01 is 11017.
        assert_eq!(civil_from_days(11_016), (2000, 2));
        assert_eq!(civil_from_days(11_017), (2000, 3));
    }

    #[test]
    fn keys_carry_their_kind() {
        assert_eq!(key_of(PERIOD_DAY, 5), Some(0x1000_0005));
        assert_eq!(key_of(PERIOD_WEEK, 5), Some(0x2000_0005));
        assert_eq!(key_of(PERIOD_MONTH, 5), Some(0x3000_0005));
        assert_eq!(key_of(4, 5), None);
        assert_eq!(key_of(PERIOD_DAY, INDEX_LIMIT), None);
        assert!(key_valid(0x3000_0005));
        assert!(!key_valid(0x4000_0005));
        assert!(!key_valid(5));
    }

    #[test]
    fn bounds_span_their_period() {
        // January 1970 is 31 days; February 2000 is 29 (a leap year a century rule would miss); week 1 opens on Monday
        // 1970-01-05; day 5 is one day. An inverse that ignored leap years, or counted weeks from the epoch, misses here.
        let m = |i| key_of(PERIOD_MONTH, i).expect("key");
        assert_eq!(bounds(m(0)), Some((0, 31 * DAY_MS)));
        assert_eq!(
            bounds(m(30 * 12 + 1)),
            Some((10_988 * DAY_MS, 11_017 * DAY_MS))
        );
        assert_eq!(
            bounds(m(30 * 12 + 11)),
            Some((
                days_from_civil(2000, 12) * DAY_MS,
                days_from_civil(2001, 1) * DAY_MS
            ))
        );
        assert_eq!(days_from_civil(2001, 1), 11_323);
        let w = key_of(PERIOD_WEEK, 1).expect("key");
        assert_eq!(bounds(w), Some((4 * DAY_MS, 11 * DAY_MS)));
        let d = key_of(PERIOD_DAY, 5).expect("key");
        assert_eq!(bounds(d), Some((5 * DAY_MS, 6 * DAY_MS)));
        assert_eq!(bounds(0x4000_0005), None);
        // Each month's bounds hold its own days and no other's, over four centuries of leap rules.
        for i in 0..(400 * 12) {
            let (from, to) = bounds(m(i)).expect("bounds");
            assert_eq!(period_index(PERIOD_MONTH, from), Some(i));
            assert_eq!(period_index(PERIOD_MONTH, to - 1), Some(i));
            assert_eq!(period_index(PERIOD_MONTH, to), Some(i + 1));
        }
    }

    #[test]
    fn a_later_salary_takes_over_at_its_own_period() {
        let day = |i: u64| key_of(PERIOD_DAY, i).expect("key");
        // Terms from day 10, a later salary from day 12 (noon): days 10 and 11 are this one's, 12 the later one's.
        let now = 20 * DAY_MS;
        assert_eq!(
            owed_keys(PERIOD_DAY, 10 * DAY_MS, Some(12 * DAY_MS + DAY_MS / 2), now),
            vec![day(10), day(11)]
        );
        // A later salary that starts before these terms leaves them nothing.
        assert!(owed_keys(PERIOD_DAY, 10 * DAY_MS, Some(9 * DAY_MS), now).is_empty());
        // No later salary: every finished day up to now.
        assert_eq!(
            owed_keys(PERIOD_DAY, 18 * DAY_MS, None, now),
            vec![day(18), day(19)]
        );
    }

    #[test]
    fn far_future_has_no_key() {
        assert_eq!(period_key(PERIOD_DAY, u64::MAX), None);
        assert!(finished_keys(PERIOD_DAY, 0, u64::MAX).is_empty());
    }
}
