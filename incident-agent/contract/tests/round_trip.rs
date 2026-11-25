use contract::{
    Action, ActionRequest, ActionResult, DiscordUserId, GitSha, IncidentId, Outcome, RequestId,
    TotpCode,
};

const SHA: &str = "1f2a9320000000000000000000000000000000ab";

fn request(action: Action) -> ActionRequest {
    ActionRequest::new(
        RequestId::try_from("req-7".to_owned()).unwrap(),
        IncidentId::try_from("inc-3".to_owned()).unwrap(),
        action,
        DiscordUserId::try_from("1234567890".to_owned()).unwrap(),
        TotpCode::try_from("042137".to_owned()).unwrap(),
        1_764_120_000,
        1_764_120_600,
    )
    .unwrap()
}

fn rollback() -> Action {
    Action::Rollback {
        target_sha: GitSha::try_from(SHA.to_owned()).unwrap(),
    }
}

#[test]
fn action_request_keeps_every_value_after_writing_and_reading() {
    for request in [request(Action::RestartBackend {}), request(rollback())] {
        let read_back: ActionRequest =
            serde_json::from_str(&serde_json::to_string(&request).unwrap()).unwrap();
        assert_eq!(read_back, request);
    }
}

#[test]
fn action_result_keeps_every_value_after_writing_and_reading() {
    let outcomes = [
        Outcome::Executed {},
        Outcome::Rejected {
            reason: "totp mismatch".to_owned(),
        },
        Outcome::Failed {
            reason: "docker exited 1".to_owned(),
        },
    ];
    for outcome in outcomes {
        let result = ActionResult::new(
            RequestId::try_from("req-1".to_owned()).unwrap(),
            outcome,
            10,
        );
        let read_back: ActionResult =
            serde_json::from_str(&serde_json::to_string(&result).unwrap()).unwrap();
        assert_eq!(read_back, result);
    }
}

#[test]
fn action_request_json_shape_is_fixed_between_analyzer_and_executor() {
    let json = r#"{"request_id":"req-7","incident_id":"inc-3","action":{"kind":"rollback","target_sha":"1f2a9320000000000000000000000000000000ab"},"requested_by":"1234567890","totp_code":"042137","issued_at_unix":1764120000,"expires_at_unix":1764120600}"#;

    assert_eq!(serde_json::to_string(&request(rollback())).unwrap(), json);
    assert_eq!(
        serde_json::from_str::<ActionRequest>(json).unwrap(),
        request(rollback())
    );
}

#[test]
fn action_result_json_shape_is_fixed_between_analyzer_and_executor() {
    let json = r#"{"request_id":"req-1","outcome":{"kind":"rejected","reason":"totp mismatch"},"finished_at_unix":10}"#;
    let result = ActionResult::new(
        RequestId::try_from("req-1".to_owned()).unwrap(),
        Outcome::Rejected {
            reason: "totp mismatch".to_owned(),
        },
        10,
    );

    assert_eq!(serde_json::to_string(&result).unwrap(), json);
    assert_eq!(serde_json::from_str::<ActionResult>(json).unwrap(), result);
}

fn valid_request_json() -> serde_json::Value {
    serde_json::to_value(request(rollback())).unwrap()
}

#[test]
fn request_files_with_unsafe_or_inconsistent_values_are_rejected() {
    let cases: [(&str, serde_json::Value); 9] = [
        ("request_id", "../../etc/passwd".into()),
        ("incident_id", "".into()),
        ("requested_by", "12ab".into()),
        ("totp_code", "12345".into()),
        ("totp_code", "12345a".into()),
        ("expires_at_unix", 1_764_120_000.into()),
        ("expires_at_unix", 1_764_121_201.into()),
        ("unexpected", "x".into()),
        (
            "action",
            serde_json::json!({"kind": "rollback", "target_sha": "--force"}),
        ),
    ];
    for (field, value) in cases {
        let mut json = valid_request_json();
        json[field] = value;
        assert!(
            serde_json::from_value::<ActionRequest>(json).is_err(),
            "{field} should be rejected"
        );
    }
}

#[test]
fn unknown_action_or_extra_action_fields_are_rejected() {
    for action in [
        serde_json::json!({"kind": "restart_backend", "target_sha": SHA}),
        serde_json::json!({"kind": "drop_database"}),
        serde_json::json!({"kind": "rollback", "target_sha": SHA.to_uppercase()}),
    ] {
        let mut json = valid_request_json();
        json["action"] = action.clone();
        assert!(
            serde_json::from_value::<ActionRequest>(json).is_err(),
            "{action} should be rejected"
        );
    }
}

#[test]
fn unknown_outcome_fields_are_rejected() {
    let json = r#"{"request_id":"req-1","outcome":{"kind":"executed","reason":"x"},"finished_at_unix":10}"#;

    assert!(serde_json::from_str::<ActionResult>(json).is_err());
}

#[test]
fn totp_code_is_hidden_in_debug_output() {
    let printed = format!("{:?}", request(Action::RestartBackend {}));

    assert!(!printed.contains("042137"));
}
