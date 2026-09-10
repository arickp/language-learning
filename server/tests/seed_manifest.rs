#[path = "../src/seed.rs"]
mod seed;

#[test]
fn passport_ihr_means_possessive_your_not_subject_you() {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("seed/manifest.json");
    let root = seed::load(&path).unwrap();
    let matches: Vec<_> = root["questions"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|q| {
            q["language"] == "GERMAN" && q["prompt"] == "In “Ist das Ihr Pass?,” Ihr means…"
        })
        .collect();
    assert_eq!(matches.len(), 1);
    assert_eq!(matches[0]["answer"], "your (formal)");
}

#[test]
fn loads_all_manifest_sections_with_language_and_shared_emojis() {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("seed/manifest.json");
    let root = seed::load(&path).unwrap();
    let manifest: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap();
    let mut vocabulary_count = 0;
    let mut question_count = 0;
    for (language, sections) in manifest["languages"].as_object().unwrap() {
        for (section, reference) in sections.as_object().unwrap() {
            let entries: serde_json::Value = serde_json::from_str(
                &std::fs::read_to_string(
                    path.parent()
                        .unwrap()
                        .join(reference["path"].as_str().unwrap()),
                )
                .unwrap(),
            )
            .unwrap();
            if section == "grammar" {
                question_count += entries.as_array().unwrap().len();
            } else {
                vocabulary_count += entries.as_array().unwrap().len();
            }
        }
        assert!(
            root["vocabulary"]
                .as_array()
                .unwrap()
                .iter()
                .any(|v| v["language"] == language.to_uppercase())
        );
    }
    assert_eq!(
        root["vocabulary"].as_array().unwrap().len(),
        vocabulary_count
    );
    assert_eq!(root["questions"].as_array().unwrap().len(), question_count);
    assert!(
        root["vocabulary"]
            .as_array()
            .unwrap()
            .iter()
            .any(|v| v["term"] == "nehmen")
    );
    assert!(
        root["vocabulary"]
            .as_array()
            .unwrap()
            .iter()
            .any(|v| v["term"] == "au revoir")
    );
    assert_eq!(root["emojiByTranslation"]["to say"], "💬");
}

#[test]
fn rejects_invalid_manifests_and_referenced_content() {
    use serde_json::json;
    let dir = std::env::temp_dir().join(format!("seed-test-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("manifest.json");
    let valid = json!({"formatVersion":1,"languages":{"french":{"verbs":{"path":"verbs.json"}}},"shared":{
        "categories":{"path":"categories.json"},"levels":{"path":"levels.json"},"emojiByTranslation":{"path":"emojis.json"}}});
    std::fs::write(
        dir.join("verbs.json"),
        r#"[{"term":"dire","translation":"to say"}]"#,
    )
    .unwrap();
    std::fs::write(dir.join("categories.json"), "[]").unwrap();
    std::fs::write(dir.join("levels.json"), "[]").unwrap();
    std::fs::write(dir.join("emojis.json"), "{}").unwrap();
    std::fs::write(&path, valid.to_string()).unwrap();
    assert_eq!(
        seed::load(&path).unwrap()["vocabulary"][0]["language"],
        "FRENCH"
    );
    for (pointer, value) in [
        ("/formatVersion", json!(2)),
        ("/languages/french/verbs/path", json!("missing.json")),
        ("/languages/french/verbs/path", json!("../outside.json")),
        ("/languages/french/verbs/path", json!("/tmp/outside.json")),
    ] {
        let mut invalid = valid.clone();
        *invalid.pointer_mut(pointer).unwrap() = value;
        std::fs::write(&path, invalid.to_string()).unwrap();
        assert!(seed::load(&path).is_err(), "{pointer}");
    }
    std::fs::write(&path, valid.to_string()).unwrap();
    for invalid in [
        "{broken",
        "{}",
        r#"[{"term":"dire"}]"#,
        r#"[{"language":"GERMAN","term":"dire","translation":"to say"}]"#,
    ] {
        std::fs::write(dir.join("verbs.json"), invalid).unwrap();
        assert!(seed::load(&path).is_err(), "{invalid}");
    }
    std::fs::remove_dir_all(dir).unwrap();
}
