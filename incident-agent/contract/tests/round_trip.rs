use contract::{Action, ActionRequest, ActionResult, Outcome};

#[test]
fn action_request_keeps_every_value_after_writing_and_reading() {
    let requests = [
        Action::RestartBackend,
        Action::Rollback {
            target_sha: "1f2a9320000000000000000000000000000000ab".to_owned(),
        },
    ]
    .map(|action| ActionRequest {
        request_id: "req-7".to_owned(),
        incident_id: "inc-3".to_owned(),
        action,
        requested_by: "1234567890".to_owned(),
        totp_code: "042137".to_owned(),
        issued_at_unix: 1_764_120_000,
        expires_at_unix: 1_764_120_600,
    });

    for request in requests {
        let read_back: ActionRequest =
            serde_json::from_str(&serde_json::to_string(&request).unwrap()).unwrap();
        assert_eq!(read_back, request);
    }
}

#[test]
fn action_result_keeps_every_value_after_writing_and_reading() {
    let results = [
        Outcome::Executed,
        Outcome::Rejected {
            reason: "totp mismatch".to_owned(),
        },
        Outcome::Failed {
            reason: "docker exited 1".to_owned(),
        },
    ]
    .map(|outcome| ActionResult {
        request_id: "req-1".to_owned(),
        outcome,
        finished_at_unix: 10,
    });

    for result in results {
        let read_back: ActionResult =
            serde_json::from_str(&serde_json::to_string(&result).unwrap()).unwrap();
        assert_eq!(read_back, result);
    }
}
