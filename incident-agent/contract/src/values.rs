use std::fmt;

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InvalidValue {
    field: &'static str,
}

impl InvalidValue {
    pub(crate) fn new(field: &'static str) -> Self {
        Self { field }
    }
}

impl fmt::Display for InvalidValue {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "invalid value for {}", self.field)
    }
}

impl std::error::Error for InvalidValue {}

macro_rules! string_value {
    ($(#[$meta:meta])* $name:ident, $field:literal, $is_valid:expr) => {
        $(#[$meta])*
        #[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
        #[serde(try_from = "String", into = "String")]
        pub struct $name(String);

        impl $name {
            pub fn as_str(&self) -> &str {
                &self.0
            }
        }

        impl TryFrom<String> for $name {
            type Error = InvalidValue;

            fn try_from(value: String) -> Result<Self, Self::Error> {
                if $is_valid(value.as_str()) {
                    Ok(Self(value))
                } else {
                    Err(InvalidValue::new($field))
                }
            }
        }

        impl From<$name> for String {
            fn from(value: $name) -> Self {
                value.0
            }
        }
    };
}

fn is_slug(value: &str) -> bool {
    (1..=64).contains(&value.len())
        && value
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
}

fn is_discord_id(value: &str) -> bool {
    (1..=20).contains(&value.len()) && value.bytes().all(|b| b.is_ascii_digit())
}

fn is_totp(value: &str) -> bool {
    value.len() == 6 && value.bytes().all(|b| b.is_ascii_digit())
}

fn is_git_sha(value: &str) -> bool {
    value.len() == 40
        && value
            .bytes()
            .all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
}

string_value!(
    #[derive(Debug)]
    RequestId,
    "request_id",
    is_slug
);
string_value!(
    #[derive(Debug)]
    IncidentId,
    "incident_id",
    is_slug
);
string_value!(
    #[derive(Debug)]
    DiscordUserId,
    "requested_by",
    is_discord_id
);
string_value!(
    #[derive(Debug)]
    GitSha,
    "target_sha",
    is_git_sha
);
string_value!(TotpCode, "totp_code", is_totp);

impl fmt::Debug for TotpCode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("TotpCode(***)")
    }
}
