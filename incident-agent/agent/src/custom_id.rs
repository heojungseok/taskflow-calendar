use std::fmt;
use std::str::FromStr;

use contract::GitSha;
use incident::ProposedAction;

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum CustomId {
    Correct {
        incident: u64,
    },
    Wrong {
        incident: u64,
    },
    Act {
        incident: u64,
        action: ProposedAction,
        issued_at_unix: u64,
    },
}

impl fmt::Display for CustomId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Correct { incident } => write!(f, "v:{incident}:ok"),
            Self::Wrong { incident } => write!(f, "v:{incident}:no"),
            Self::Act {
                incident,
                action: ProposedAction::RestartBackend,
                issued_at_unix,
            } => write!(f, "a:{incident}:restart:{issued_at_unix}"),
            Self::Act {
                incident,
                action: ProposedAction::Rollback { target_sha },
                issued_at_unix,
            } => write!(
                f,
                "a:{incident}:rollback:{}:{issued_at_unix}",
                target_sha.as_str()
            ),
        }
    }
}

impl FromStr for CustomId {
    type Err = ();

    fn from_str(text: &str) -> Result<Self, Self::Err> {
        let parts: Vec<&str> = text.split(':').collect();
        let number = |part: &str| part.parse::<u64>().map_err(|_| ());
        match parts.as_slice() {
            ["v", incident, "ok"] => Ok(Self::Correct {
                incident: number(incident)?,
            }),
            ["v", incident, "no"] => Ok(Self::Wrong {
                incident: number(incident)?,
            }),
            ["a", incident, "restart", issued] => Ok(Self::Act {
                incident: number(incident)?,
                action: ProposedAction::RestartBackend,
                issued_at_unix: number(issued)?,
            }),
            ["a", incident, "rollback", sha, issued] => Ok(Self::Act {
                incident: number(incident)?,
                action: ProposedAction::Rollback {
                    target_sha: GitSha::try_from((*sha).to_owned()).map_err(|_| ())?,
                },
                issued_at_unix: number(issued)?,
            }),
            _ => Err(()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::T0;

    const SHA: &str = "e48a8e1a0ec361934fd097afaa9ddd0e48cb0c69";

    fn sha() -> GitSha {
        GitSha::try_from(SHA.to_owned()).unwrap()
    }

    #[test]
    fn t39_custom_id는_직렬화_왕복이_된다() {
        let all = [
            CustomId::Correct { incident: 1 },
            CustomId::Wrong { incident: u64::MAX },
            CustomId::Act {
                incident: 2,
                action: ProposedAction::RestartBackend,
                issued_at_unix: T0,
            },
            CustomId::Act {
                incident: 3,
                action: ProposedAction::Rollback { target_sha: sha() },
                issued_at_unix: T0,
            },
        ];
        for id in all {
            let text = id.to_string();
            assert!(text.len() <= 100);
            assert_eq!(text.parse::<CustomId>(), Ok(id));
        }
    }

    #[test]
    fn t39_엉터리_custom_id는_거절한다() {
        for bad in [
            "",
            "x",
            "v:abc:ok",
            "v:1",
            "v:1:maybe",
            "a:1:restart",
            "a:1:rollback:zz:5",
            "a:1:nuke:5",
            "a:1:restart:5:extra",
            "v:1:ok:extra",
        ] {
            assert!(bad.parse::<CustomId>().is_err(), "{bad}");
        }
    }
}
