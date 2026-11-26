mod common;

use std::time::Duration;

use analyzer::{ANSWER_SCHEMA, Model};
use common::{OK_BODY, serve};
use ollama::Ollama;

#[test]
fn request_forces_the_answer_schema_and_separates_instructions_from_the_prompt() {
    let server = serve(200, OK_BODY, Duration::ZERO);
    let model = Ollama::new(&server.url, "qwen")
        .think(false)
        .keep_alive("0");

    model.explain("증거 프롬프트").unwrap();

    let request = server.request_body();
    let schema: serde_json::Value = serde_json::from_str(ANSWER_SCHEMA).unwrap();
    assert_eq!(request["model"], "qwen");
    assert_eq!(request["stream"], false);
    assert_eq!(request["format"], schema);
    assert_eq!(request["keep_alive"], "0");
    assert_eq!(request["think"], false);
    assert_eq!(request["options"]["temperature"], 0.0);
    assert_eq!(request["messages"][0]["role"], "system");
    assert_eq!(request["messages"][1]["role"], "user");
    assert_eq!(request["messages"][1]["content"], "증거 프롬프트");
}
