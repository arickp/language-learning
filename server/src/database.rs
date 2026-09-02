use serde_json::{Value, json};
use sqlx::{Row, SqlitePool, sqlite::SqliteConnectOptions};
use std::{path::Path, str::FromStr};

pub async fn connect(database_url: &str) -> Result<SqlitePool, sqlx::Error> {
    if let Some(path) = database_url.strip_prefix("sqlite://") {
        if let Some(parent) = Path::new(path).parent() {
            std::fs::create_dir_all(parent).ok();
        }
    }
    let options = SqliteConnectOptions::from_str(database_url)?
        .create_if_missing(true)
        .foreign_keys(true);
    let pool = SqlitePool::connect_with(options).await?;
    sqlx::query(
        "CREATE TABLE IF NOT EXISTS vocabulary (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            language TEXT NOT NULL CHECK(language IN ('GERMAN','FRENCH')),
            term TEXT NOT NULL,
            translation TEXT NOT NULL,
            article TEXT,
            noun TEXT,
            difficulty TEXT NOT NULL DEFAULT 'UNRANKED',
            variant TEXT,
            spoken_language TEXT,
            date_added TEXT,
            explicit INTEGER NOT NULL DEFAULT 0,
            emoji TEXT,
            UNIQUE(language, term, translation)
        )",
    )
    .execute(&pool)
    .await?;
    ensure_column(
        &pool,
        "vocabulary",
        "difficulty",
        "TEXT NOT NULL DEFAULT 'UNRANKED'",
    )
    .await?;
    ensure_column(&pool, "vocabulary", "variant", "TEXT").await?;
    ensure_column(&pool, "vocabulary", "spoken_language", "TEXT").await?;
    ensure_column(&pool, "vocabulary", "date_added", "TEXT").await?;
    ensure_column(
        &pool,
        "vocabulary",
        "explicit",
        "INTEGER NOT NULL DEFAULT 0",
    )
    .await?;
    ensure_column(&pool, "vocabulary", "emoji", "TEXT").await?;
    sqlx::query(
        "CREATE TABLE IF NOT EXISTS questions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            language TEXT NOT NULL CHECK(language IN ('GERMAN','FRENCH')),
            category TEXT NOT NULL CHECK(category IN ('VOCABULARY','ARTICLES','GRAMMAR')),
            prompt TEXT NOT NULL,
            answer TEXT NOT NULL,
            explanation TEXT NOT NULL DEFAULT '',
            translation TEXT,
            hints_json TEXT NOT NULL DEFAULT '[]',
            spoken_text TEXT,
            difficulty TEXT NOT NULL DEFAULT 'UNRANKED',
            UNIQUE(language, category, prompt, answer)
        )",
    )
    .execute(&pool)
    .await?;
    ensure_column(
        &pool,
        "questions",
        "difficulty",
        "TEXT NOT NULL DEFAULT 'UNRANKED'",
    )
    .await?;
    rank_unranked_content(&pool).await?;
    Ok(pool)
}

async fn ensure_column(
    pool: &SqlitePool,
    table: &str,
    column: &str,
    definition: &str,
) -> Result<(), sqlx::Error> {
    let columns = sqlx::query(&format!("PRAGMA table_info({table})"))
        .fetch_all(pool)
        .await?;
    if !columns
        .iter()
        .any(|row| row.get::<String, _>("name") == column)
    {
        sqlx::query(&format!(
            "ALTER TABLE {table} ADD COLUMN {column} {definition}"
        ))
        .execute(pool)
        .await?;
    }
    Ok(())
}

async fn rank_unranked_content(pool: &SqlitePool) -> Result<(), sqlx::Error> {
    let rows = sqlx::query(
        "SELECT id,language,term,translation FROM vocabulary WHERE difficulty='UNRANKED'",
    )
    .fetch_all(pool)
    .await?;
    for row in rows {
        let difficulty = rank_vocabulary(
            &row.get::<String, _>("language"),
            &row.get::<String, _>("term"),
            &row.get::<String, _>("translation"),
        );
        sqlx::query("UPDATE vocabulary SET difficulty=? WHERE id=?")
            .bind(difficulty)
            .bind(row.get::<i64, _>("id"))
            .execute(pool)
            .await?;
    }
    let rows = sqlx::query("SELECT id,prompt,answer FROM questions WHERE difficulty='UNRANKED'")
        .fetch_all(pool)
        .await?;
    for row in rows {
        let prompt = row.get::<String, _>("prompt");
        let answer = row.get::<String, _>("answer");
        let difficulty = rank_question(&prompt, &answer);
        sqlx::query("UPDATE questions SET difficulty=? WHERE id=?")
            .bind(difficulty)
            .bind(row.get::<i64, _>("id"))
            .execute(pool)
            .await?;
    }
    Ok(())
}

fn seed_explicit(entry: &Value) -> i64 {
    if entry
        .get("explicit")
        .and_then(Value::as_bool)
        .unwrap_or(false)
    {
        1
    } else {
        0
    }
}

fn seed_emoji<'a>(root: &'a Value, entry: &'a Value) -> Option<&'a str> {
    entry.get("emoji").and_then(Value::as_str).or_else(|| {
        let translation = entry.get("translation").and_then(Value::as_str)?;
        root.get("emojiByTranslation")
            .and_then(|map| map.get(translation))
            .and_then(Value::as_str)
    })
}

fn rank_question(prompt: &str, answer: &str) -> &'static str {
    if prompt.contains("accusative")
        || prompt.contains("dative")
        || prompt.contains("Dativ")
        || prompt.contains("Akkusativ")
        || prompt.contains("genitive")
    {
        "HARD"
    } else if prompt.chars().count() < 55 && answer.chars().count() < 14 {
        "EASY"
    } else {
        "MEDIUM"
    }
}

fn rank_vocabulary(language: &str, term: &str, translation: &str) -> &'static str {
    let term_lower = term.to_lowercase();
    let meaning = translation.to_lowercase();
    let easy_meanings = [
        "girl",
        "boy",
        "child",
        "mother",
        "mom",
        "father",
        "dad",
        "family",
        "house",
        "home",
        "hello",
        "goodbye",
        "please",
        "thank you",
        "yes",
        "no",
        "who",
        "book",
        "school",
        "friend",
        "friends",
        "car",
        "bus",
        "train",
        "water",
        "food",
        "dog",
        "cat",
        "one",
        "two",
        "three",
        "four",
        "five",
        "six",
        "seven",
        "eight",
        "nine",
        "ten",
        "zero",
        "phone",
        "computer",
        "app",
        "message",
        "park",
        "city",
        "happy",
        "tired",
        "now",
        "today",
        "tomorrow",
        "yesterday",
    ];
    if easy_meanings
        .iter()
        .any(|word| meaning == *word || meaning.starts_with(&format!("{word} /")))
    {
        return "EASY";
    }
    let hard_french_tech = [
        "courriel",
        "progiciel",
        "ordiphone",
        "hyperlien",
        "téléverse",
        "infonuagique",
        "clavard",
        "pourriel",
        "polluriel",
        "hameçonnage",
        "rançongiciel",
        "mot-dièse",
        "binette",
        "égoportrait",
        "balado",
        "gazouillis",
        "infolettre",
        "webmestre",
        "arobase",
    ];
    if language == "FRENCH"
        && hard_french_tech
            .iter()
            .any(|word| term_lower.contains(word))
    {
        return "HARD";
    }
    let specialist_meanings = [
        "ransomware",
        "malware",
        "spyware",
        "phishing",
        "hypertext",
        "cloud computing",
        "cybersecurity",
        "cyberattack",
        "augmented reality",
        "virtual reality",
        "podcasting",
        "videoconference",
        "rechargeable battery",
        "electrical outlet",
        "institutional alternative",
    ];
    if specialist_meanings
        .iter()
        .any(|word| meaning.contains(word))
        || term.split_whitespace().count() >= 5
        || term.chars().count() >= 30
        || term.contains(" / ")
    {
        "HARD"
    } else {
        "MEDIUM"
    }
}

pub async fn seed_if_empty(
    pool: &SqlitePool,
    seed_json: &str,
) -> Result<(), Box<dyn std::error::Error>> {
    let vocabulary_count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM vocabulary")
        .fetch_one(pool)
        .await?;
    let question_count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM questions")
        .fetch_one(pool)
        .await?;
    if vocabulary_count != 0 || question_count != 0 {
        return Ok(());
    }
    let root: Value = serde_json::from_str(seed_json)?;
    let mut tx = pool.begin().await?;
    for entry in root["vocabulary"].as_array().into_iter().flatten() {
        let language = entry
            .get("language")
            .and_then(Value::as_str)
            .unwrap_or("GERMAN");
        let term = entry["term"].as_str().unwrap_or_default();
        let translation = entry["translation"].as_str().unwrap_or_default();
        let difficulty = entry
            .get("difficulty")
            .and_then(Value::as_str)
            .unwrap_or_else(|| rank_vocabulary(language, term, translation));
        sqlx::query("INSERT OR IGNORE INTO vocabulary(language,term,translation,article,noun,difficulty,variant,spoken_language,date_added,explicit,emoji) VALUES(?,?,?,?,?,?,?,?,?,?,?)")
            .bind(language)
            .bind(term)
            .bind(translation)
            .bind(entry.get("article").and_then(Value::as_str))
            .bind(entry.get("noun").and_then(Value::as_str))
            .bind(difficulty)
            .bind(entry.get("variant").and_then(Value::as_str))
            .bind(entry.get("spokenLanguage").and_then(Value::as_str))
            .bind(entry.get("dateAdded").and_then(Value::as_str))
            .bind(seed_explicit(entry))
            .bind(seed_emoji(&root, entry))
            .execute(&mut *tx).await?;
    }
    for entry in root["questions"].as_array().into_iter().flatten() {
        let prompt = entry["prompt"].as_str().unwrap_or_default();
        let answer = entry["answer"].as_str().unwrap_or_default();
        sqlx::query("INSERT OR IGNORE INTO questions(language,category,prompt,answer,explanation,translation,hints_json,spoken_text,difficulty) VALUES(?,?,?,?,?,?,?,?,?)")
            .bind(entry.get("language").and_then(Value::as_str).unwrap_or("GERMAN"))
            .bind(entry["category"].as_str().unwrap_or("GRAMMAR"))
            .bind(prompt)
            .bind(answer)
            .bind(entry.get("explanation").and_then(Value::as_str).unwrap_or(""))
            .bind(entry.get("translation").and_then(Value::as_str))
            .bind(entry.get("hints").map(Value::to_string).unwrap_or_else(|| "[]".into()))
            .bind(entry.get("spokenText").and_then(Value::as_str))
            .bind(entry.get("difficulty").and_then(Value::as_str).unwrap_or_else(|| rank_question(prompt, answer)))
            .execute(&mut *tx).await?;
    }
    tx.commit().await?;
    Ok(())
}

pub async fn sync_core_vocabulary(
    pool: &SqlitePool,
    seed_json: &str,
) -> Result<(), Box<dyn std::error::Error>> {
    let root: Value = serde_json::from_str(seed_json)?;
    for entry in root["vocabulary"].as_array().into_iter().flatten() {
        let should_sync = entry.get("core").and_then(Value::as_bool).unwrap_or(false)
            || entry.get("sync").and_then(Value::as_bool).unwrap_or(false);
        let language = entry
            .get("language")
            .and_then(Value::as_str)
            .unwrap_or("GERMAN");
        let term = entry["term"].as_str().unwrap_or_default();
        let translation = entry["translation"].as_str().unwrap_or_default();
        let article = entry.get("article").and_then(Value::as_str);
        let noun = entry.get("noun").and_then(Value::as_str);
        let difficulty = entry
            .get("difficulty")
            .and_then(Value::as_str)
            .unwrap_or_else(|| {
                if should_sync {
                    "EASY"
                } else {
                    rank_vocabulary(language, term, translation)
                }
            });
        let variant = entry.get("variant").and_then(Value::as_str);
        let spoken_language = entry.get("spokenLanguage").and_then(Value::as_str);
        let date_added = entry.get("dateAdded").and_then(Value::as_str);
        let explicit = seed_explicit(entry);
        let emoji = seed_emoji(&root, entry);
        let existing_id: Option<i64> = sqlx::query_scalar(
            "SELECT id FROM vocabulary WHERE language=? AND term=? ORDER BY (translation=?) DESC,id LIMIT 1",
        )
        .bind(language)
        .bind(term)
        .bind(translation)
        .fetch_optional(pool)
        .await?;
        if let Some(id) = existing_id {
            sqlx::query("UPDATE vocabulary SET explicit=?,emoji=COALESCE(emoji,?) WHERE id=?")
                .bind(explicit)
                .bind(emoji)
                .bind(id)
                .execute(pool)
                .await?;
            if should_sync {
                sqlx::query("UPDATE vocabulary SET translation=?,article=?,noun=?,difficulty=?,variant=?,spoken_language=?,date_added=COALESCE(?,date_added) WHERE id=?")
                    .bind(translation).bind(article).bind(noun).bind(difficulty).bind(variant).bind(spoken_language).bind(date_added).bind(id)
                    .execute(pool).await?;
            }
        } else {
            sqlx::query("INSERT INTO vocabulary(language,term,translation,article,noun,difficulty,variant,spoken_language,date_added,explicit,emoji) VALUES(?,?,?,?,?,?,?,?,?,?,?)")
                .bind(language).bind(term).bind(translation).bind(article).bind(noun).bind(difficulty).bind(variant).bind(spoken_language).bind(date_added).bind(explicit).bind(emoji)
                .execute(pool).await?;
        }
    }
    Ok(())
}

pub async fn export(pool: &SqlitePool) -> Result<Value, sqlx::Error> {
    let vocab_rows = sqlx::query(
        "SELECT id,language,term,translation,article,noun,difficulty,variant,spoken_language,date_added,explicit,emoji FROM vocabulary ORDER BY language,term",
    )
    .fetch_all(pool)
    .await?;
    let question_rows = sqlx::query("SELECT id,language,category,prompt,answer,explanation,translation,hints_json,spoken_text,difficulty FROM questions ORDER BY language,category,prompt").fetch_all(pool).await?;
    let vocabulary = vocab_rows.into_iter().map(|row| json!({
        "id": row.get::<i64,_>("id"), "language": row.get::<String,_>("language"),
        "term": row.get::<String,_>("term"), "translation": row.get::<String,_>("translation"),
        "article": row.get::<Option<String>,_>("article"), "noun": row.get::<Option<String>,_>("noun"),
        "difficulty": row.get::<String,_>("difficulty"), "variant": row.get::<Option<String>,_>("variant"),
        "spokenLanguage": row.get::<Option<String>,_>("spoken_language"),
        "dateAdded": row.get::<Option<String>,_>("date_added"),
        "explicit": row.get::<i64,_>("explicit") != 0,
        "emoji": row.get::<Option<String>,_>("emoji")
    })).collect::<Vec<_>>();
    let questions = question_rows.into_iter().map(|row| {
        let hints: Value = serde_json::from_str(&row.get::<String,_>("hints_json")).unwrap_or(json!([]));
        json!({
            "id": row.get::<i64,_>("id"), "language": row.get::<String,_>("language"),
            "category": row.get::<String,_>("category"), "prompt": row.get::<String,_>("prompt"),
            "answer": row.get::<String,_>("answer"), "explanation": row.get::<String,_>("explanation"),
            "translation": row.get::<Option<String>,_>("translation"), "hints": hints,
            "spokenText": row.get::<Option<String>,_>("spoken_text"),
            "difficulty": row.get::<String,_>("difficulty")
        })
    }).collect::<Vec<_>>();
    Ok(json!({"formatVersion":2,"vocabulary":vocabulary,"questions":questions}))
}
