//! Load the versioned split seed before opening or modifying SQLite.
use serde::Deserialize;
use serde_json::{Value, json};
use std::{collections::BTreeMap, path::Path};

type Error = Box<dyn std::error::Error>;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Manifest {
    format_version: u32,
    languages: BTreeMap<String, BTreeMap<String, Reference>>,
    shared: BTreeMap<String, Reference>,
}

#[derive(Deserialize)]
struct Reference {
    path: String,
}

fn read_json(path: &Path) -> Result<Value, Error> {
    let text = std::fs::read_to_string(path)
        .map_err(|e| format!("cannot read seed {}: {e}", path.display()))?;
    Ok(serde_json::from_str(&text)
        .map_err(|e| format!("invalid seed JSON {}: {e}", path.display()))?)
}

pub fn load(path: &Path) -> Result<Value, Error> {
    let manifest: Manifest = serde_json::from_value(read_json(path)?)?;
    if manifest.format_version != 1 || manifest.languages.is_empty() {
        return Err("seed manifest requires formatVersion 1 and languages".into());
    }
    let base = path.parent().unwrap_or(Path::new(".")).canonicalize()?;
    let read = |reference: &Reference| -> Result<Value, Error> {
        let relative = Path::new(&reference.path);
        if relative.is_absolute()
            || relative
                .components()
                .any(|c| !matches!(c, std::path::Component::Normal(_)))
        {
            return Err(format!(
                "seed path must be relative without traversal: {}",
                reference.path
            )
            .into());
        }
        let resolved = base
            .join(relative)
            .canonicalize()
            .map_err(|e| format!("cannot resolve seed {}: {e}", relative.display()))?;
        if !resolved.starts_with(&base) {
            return Err(format!("seed path escapes manifest directory: {}", reference.path).into());
        }
        read_json(&resolved)
    };
    let mut vocabulary = Vec::new();
    let mut questions = Vec::new();
    for (language, sections) in manifest.languages {
        let language = match language.as_str() {
            "german" => "GERMAN",
            "french" => "FRENCH",
            _ => return Err(format!("unsupported seed language: {language}").into()),
        };
        for (section, reference) in sections {
            let is_question = match section.as_str() {
                "grammar" => true,
                "vocabulary" | "verbs" | "phrases" => false,
                _ => return Err(format!("unsupported seed section: {section}").into()),
            };
            let data = read(&reference)?;
            let entries = data
                .as_array()
                .ok_or_else(|| format!("{} must contain an array", reference.path))?;
            for entry in entries {
                let mut entry = entry
                    .as_object()
                    .ok_or("seed entries must be objects")?
                    .clone();
                if let Some(existing) = entry.get("language") {
                    if existing.as_str() != Some(language) {
                        return Err(format!("language mismatch in {}", reference.path).into());
                    }
                }
                entry.insert("language".into(), json!(language));
                let required = if is_question {
                    ["prompt", "answer"]
                } else {
                    ["term", "translation"]
                };
                for field in required {
                    if !entry
                        .get(field)
                        .and_then(Value::as_str)
                        .is_some_and(|v| !v.trim().is_empty())
                    {
                        return Err(
                            format!("missing or empty {field} in {}", reference.path).into()
                        );
                    }
                }
                if is_question {
                    entry.entry("category").or_insert(json!("GRAMMAR"));
                    if !matches!(
                        entry["category"].as_str(),
                        Some("GRAMMAR" | "VOCABULARY" | "ARTICLES")
                    ) {
                        return Err(format!("invalid category in {}", reference.path).into());
                    }
                    questions.push(Value::Object(entry));
                } else {
                    vocabulary.push(Value::Object(entry));
                }
            }
        }
    }
    let mut root = json!({"vocabulary": vocabulary, "questions": questions});
    for key in ["categories", "levels", "emojiByTranslation"] {
        let reference = manifest
            .shared
            .get(key)
            .ok_or_else(|| format!("missing shared seed {key}"))?;
        let value = read(reference)?;
        let valid = if key == "emojiByTranslation" {
            value
                .as_object()
                .is_some_and(|m| m.values().all(Value::is_string))
        } else {
            value
                .as_array()
                .is_some_and(|a| a.iter().all(Value::is_string))
        };
        if !valid {
            return Err(format!("invalid shared seed {key}").into());
        }
        root[key] = value;
    }
    Ok(root)
}
