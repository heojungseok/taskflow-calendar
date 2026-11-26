use contract::{DiscordUserId, TotpCode};
use incident::{Approval, Event, ProposedAction, Verdict};

use crate::custom_id::CustomId;

pub(crate) struct Interaction {
    pub(crate) user_id: String,
    pub(crate) custom_id: String,
    pub(crate) submitted: Option<String>,
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) enum Reply {
    Reject(String),
    AskCode(CustomId),
    AskActualCause(CustomId),
    Accepted(String),
}

pub(crate) struct Decision {
    pub(crate) reply: Reply,
    pub(crate) event: Option<Event>,
}

const MAX_CAUSE_CHARS: usize = 500;

fn reject(text: &str) -> Decision {
    Decision {
        reply: Reply::Reject(text.to_owned()),
        event: None,
    }
}

fn accepted(text: &str, event: Event) -> Decision {
    Decision {
        reply: Reply::Accepted(text.to_owned()),
        event: Some(event),
    }
}

fn ask(reply: Reply) -> Decision {
    Decision { reply, event: None }
}

fn wrong_cause(owner: &DiscordUserId, incident: u64, text: &str) -> Decision {
    let cause = text.trim();
    if cause.is_empty() || cause.chars().count() > MAX_CAUSE_CHARS {
        return reject("실제 원인을 1~500자로 적어야 한다");
    }
    let verdict = Verdict::Wrong {
        actual_cause: cause.to_owned(),
    };
    accepted("판정을 기록했다", verdict_event(owner, incident, verdict))
}

fn verdict_event(owner: &DiscordUserId, incident: u64, verdict: Verdict) -> Event {
    Event::VerdictGiven {
        incident,
        by: owner.clone(),
        verdict,
    }
}

fn approval(
    owner: &DiscordUserId,
    (incident, action, issued_at_unix): (u64, ProposedAction, u64),
    text: &str,
) -> Decision {
    let Ok(code) = TotpCode::try_from(text.trim().to_owned()) else {
        return reject("코드는 6자리 숫자여야 한다");
    };
    let approval = Approval {
        incident,
        action,
        by: owner.clone(),
        code,
        button_issued_at_unix: issued_at_unix,
    };
    accepted("승인을 요청했다", Event::ApprovalRequested(approval))
}

pub(crate) fn decide(owner: &DiscordUserId, interaction: &Interaction) -> Decision {
    if interaction.user_id != owner.as_str() {
        return reject("소유자만 누를 수 있다");
    }
    let Ok(id) = interaction.custom_id.parse::<CustomId>() else {
        return reject("알 수 없는 버튼이다");
    };
    let submitted = interaction.submitted.as_deref();
    match (id, submitted) {
        (CustomId::Correct { incident }, _) => accepted(
            "판정을 기록했다",
            verdict_event(owner, incident, Verdict::Correct),
        ),
        (id @ CustomId::Wrong { .. }, None) => ask(Reply::AskActualCause(id)),
        (CustomId::Wrong { incident }, Some(text)) => wrong_cause(owner, incident, text),
        (id @ CustomId::Act { .. }, None) => ask(Reply::AskCode(id)),
        (
            CustomId::Act {
                incident,
                action,
                issued_at_unix,
            },
            Some(text),
        ) => approval(owner, (incident, action, issued_at_unix), text),
    }
}

#[cfg(test)]
mod tests {
    use contract::GitSha;

    use super::*;
    use crate::test_support::T0;

    const OWNER: &str = "1234567890";

    fn owner() -> DiscordUserId {
        DiscordUserId::try_from(OWNER.to_owned()).unwrap()
    }

    fn press(user: &str, id: &CustomId) -> Interaction {
        Interaction {
            user_id: user.to_owned(),
            custom_id: id.to_string(),
            submitted: None,
        }
    }

    fn submit(user: &str, id: &CustomId, value: &str) -> Interaction {
        Interaction {
            user_id: user.to_owned(),
            custom_id: id.to_string(),
            submitted: Some(value.to_owned()),
        }
    }

    fn restart() -> CustomId {
        CustomId::Act {
            incident: 7,
            action: ProposedAction::RestartBackend,
            issued_at_unix: T0,
        }
    }

    #[test]
    fn t40_소유자가_아니면_거절하고_이벤트가_없다() {
        for id in [CustomId::Correct { incident: 7 }, restart()] {
            let decision = decide(&owner(), &press("999", &id));
            assert!(matches!(decision.reply, Reply::Reject(_)));
            assert!(decision.event.is_none());
        }
        let decision = decide(&owner(), &submit("999", &restart(), "042137"));
        assert!(matches!(decision.reply, Reply::Reject(_)));
        assert!(decision.event.is_none());
    }

    #[test]
    fn t40_맞음_버튼은_판정_이벤트를_만든다() {
        let decision = decide(&owner(), &press(OWNER, &CustomId::Correct { incident: 7 }));
        assert!(matches!(decision.reply, Reply::Accepted(_)));
        assert!(matches!(
            decision.event,
            Some(Event::VerdictGiven {
                incident: 7,
                verdict: Verdict::Correct,
                ..
            })
        ));
    }

    #[test]
    fn t40_틀림_버튼은_실제_원인을_묻고_제출하면_판정이_된다() {
        let id = CustomId::Wrong { incident: 7 };
        let asked = decide(&owner(), &press(OWNER, &id));
        assert_eq!(asked.reply, Reply::AskActualCause(id.clone()));
        assert!(asked.event.is_none());
        let done = decide(&owner(), &submit(OWNER, &id, "  DB 연결 고갈  "));
        let Some(Event::VerdictGiven {
            verdict: Verdict::Wrong { actual_cause },
            ..
        }) = done.event
        else {
            panic!("판정 이벤트가 아니다");
        };
        assert_eq!(actual_cause, "DB 연결 고갈");
        let blank = decide(&owner(), &submit(OWNER, &id, "   "));
        assert!(matches!(blank.reply, Reply::Reject(_)));
        assert!(blank.event.is_none());
    }

    #[test]
    fn t40_조치_버튼은_코드를_묻고_6자리면_승인_이벤트가_된다() {
        let asked = decide(&owner(), &press(OWNER, &restart()));
        assert_eq!(asked.reply, Reply::AskCode(restart()));
        assert!(asked.event.is_none());
        let done = decide(&owner(), &submit(OWNER, &restart(), " 042137 "));
        let Some(Event::ApprovalRequested(approval)) = done.event else {
            panic!("승인 이벤트가 아니다");
        };
        assert_eq!(approval.incident, 7);
        assert_eq!(approval.action, ProposedAction::RestartBackend);
        assert_eq!(approval.by.as_str(), OWNER);
        assert_eq!(approval.code.as_str(), "042137");
        assert_eq!(approval.button_issued_at_unix, T0);
    }

    #[test]
    fn t40_되돌리기_조치는_대상_sha를_승인에_담는다() {
        let sha = "e48a8e1a0ec361934fd097afaa9ddd0e48cb0c69";
        let action = ProposedAction::Rollback {
            target_sha: GitSha::try_from(sha.to_owned()).unwrap(),
        };
        let id = CustomId::Act {
            incident: 2,
            action: action.clone(),
            issued_at_unix: T0,
        };
        let Some(Event::ApprovalRequested(approval)) =
            decide(&owner(), &submit(OWNER, &id, "000001")).event
        else {
            panic!("승인 이벤트가 아니다");
        };
        assert_eq!(approval.action, action);
    }

    #[test]
    fn t40_코드_형식이_틀리면_거절_문구이고_이벤트가_없다() {
        for bad in ["12345", "1234567", "12a456", "", "０４２１３７"] {
            let decision = decide(&owner(), &submit(OWNER, &restart(), bad));
            let Reply::Reject(text) = decision.reply else {
                panic!("거절이 아니다: {bad}")
            };
            assert!(text.contains("6자리"));
            assert!(decision.event.is_none());
        }
    }

    #[test]
    fn t40_엉터리_custom_id는_거절한다() {
        let interaction = Interaction {
            user_id: OWNER.to_owned(),
            custom_id: "zzz".to_owned(),
            submitted: None,
        };
        let decision = decide(&owner(), &interaction);
        assert!(matches!(decision.reply, Reply::Reject(_)));
        assert!(decision.event.is_none());
    }
}
