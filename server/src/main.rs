mod database;

use axum::{
    Json, Router,
    body::Body,
    extract::{Path as AxumPath, State},
    http::{HeaderMap, StatusCode, header},
    response::{Html, Response},
    routing::{get, post},
};
use reqwest::Client;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sqlx::SqlitePool;
use std::{
    collections::HashMap,
    env,
    net::SocketAddr,
    sync::{
        Arc,
        atomic::{AtomicUsize, Ordering},
    },
};
use tokio::sync::RwLock;
use url::Url;

#[derive(Clone)]
struct AppState {
    client: Client,
    api_key: String,
    model: String,
    tts_model: String,
    audio_model: String,
    cache: Arc<RwLock<HashMap<String, ExampleResult>>>,
    speech_cache: Arc<RwLock<HashMap<String, Vec<u8>>>>,
    uncached_requests: Arc<AtomicUsize>,
    max_uncached_requests: usize,
    uncached_speech_requests: Arc<AtomicUsize>,
    max_uncached_speech_requests: usize,
    practice_cache: Arc<RwLock<HashMap<String, PracticeSentence>>>,
    practice_evaluations: Arc<AtomicUsize>,
    max_practice_evaluations: usize,
    database: SqlitePool,
    admin_token: String,
}

#[derive(Deserialize)]
struct FindRequest {
    term: String,
    answer: String,
    language: String,
    #[serde(default = "default_source")]
    source: String,
    #[serde(default)]
    allow_explicit: bool,
}

#[derive(Clone, Serialize)]
struct ExampleResult {
    title: String,
    url: String,
    summary: String,
    nsfw: bool,
    source: String,
}

fn default_source() -> String {
    "reddit".into()
}

#[derive(Deserialize)]
struct SpeechRequest {
    text: String,
    language: String,
    region: String,
}

#[derive(Deserialize)]
struct VocabularyInput {
    language: String,
    term: String,
    translation: String,
    article: Option<String>,
    noun: Option<String>,
    difficulty: String,
    #[serde(default)]
    variant: Option<String>,
    #[serde(default)]
    spoken_language: Option<String>,
}

#[derive(Deserialize)]
struct QuestionInput {
    language: String,
    category: String,
    prompt: String,
    answer: String,
    #[serde(default)]
    explanation: String,
    translation: Option<String>,
    #[serde(default)]
    hints: Vec<String>,
    spoken_text: Option<String>,
    difficulty: String,
}

#[derive(Deserialize)]
struct PracticeSentenceRequest {
    term: String,
    language: String,
    region: String,
}

#[derive(Clone, Serialize, Deserialize)]
struct PracticeSentence {
    sentence: String,
    translation: String,
}

#[derive(Deserialize)]
struct PracticeEvaluationRequest {
    sentence: String,
    language: String,
    region: String,
    audio_base64: String,
}

#[tokio::main]
async fn main() {
    dotenvy::dotenv().ok();
    let api_key = env::var("OPENAI_API_KEY").expect("OPENAI_API_KEY must be set in server/.env");
    let model = env::var("OPENAI_MODEL").unwrap_or_else(|_| "gpt-5.6-luna".into());
    let tts_model = env::var("OPENAI_TTS_MODEL").unwrap_or_else(|_| "gpt-4o-mini-tts".into());
    let audio_model = env::var("OPENAI_AUDIO_MODEL").unwrap_or_else(|_| "gpt-audio-mini".into());
    let port: u16 = env::var("PORT")
        .unwrap_or_else(|_| "41082".into())
        .parse()
        .expect("PORT must be a number");
    let max_uncached_requests: usize = env::var("MAX_UNCACHED_REQUESTS")
        .unwrap_or_else(|_| "25".into())
        .parse()
        .expect("MAX_UNCACHED_REQUESTS must be a number");
    let max_uncached_speech_requests: usize = env::var("MAX_UNCACHED_SPEECH_REQUESTS")
        .unwrap_or_else(|_| "100".into())
        .parse()
        .expect("MAX_UNCACHED_SPEECH_REQUESTS must be a number");
    let max_practice_evaluations: usize = env::var("MAX_PRACTICE_EVALUATIONS")
        .unwrap_or_else(|_| "50".into())
        .parse()
        .expect("MAX_PRACTICE_EVALUATIONS must be a number");
    let database_url =
        env::var("DATABASE_URL").unwrap_or_else(|_| "sqlite://data/language-learning.db".into());
    let database = database::connect(&database_url)
        .await
        .expect("could not open SQLite database");
    database::seed_if_empty(&database, include_str!("../seed/quiz_data.json"))
        .await
        .expect("could not import initial word bank");
    database::sync_core_vocabulary(&database, include_str!("../seed/quiz_data.json"))
        .await
        .expect("could not add core beginner vocabulary");
    let admin_token = env::var("ADMIN_TOKEN").unwrap_or_default();
    if admin_token.is_empty() {
        eprintln!("Warning: ADMIN_TOKEN is not set; word-bank changes are disabled.");
    }
    let state = AppState {
        client: Client::new(),
        api_key,
        model,
        tts_model,
        audio_model,
        cache: Arc::new(RwLock::new(HashMap::new())),
        speech_cache: Arc::new(RwLock::new(HashMap::new())),
        uncached_requests: Arc::new(AtomicUsize::new(0)),
        max_uncached_requests,
        uncached_speech_requests: Arc::new(AtomicUsize::new(0)),
        max_uncached_speech_requests,
        practice_cache: Arc::new(RwLock::new(HashMap::new())),
        practice_evaluations: Arc::new(AtomicUsize::new(0)),
        max_practice_evaluations,
        database,
        admin_token,
    };
    let app = Router::new()
        .route("/", get(home_page))
        .route("/privacy", get(privacy_page))
        .route("/terms", get(terms_page))
        .route("/health", get(|| async { "ok" }))
        .route("/api/quiz-data", get(quiz_data))
        .route("/admin", get(admin_page))
        .route("/api/admin/word-bank", get(admin_word_bank))
        .route("/api/admin/vocabulary", post(create_vocabulary))
        .route(
            "/api/admin/vocabulary/{id}",
            axum::routing::put(update_vocabulary).delete(delete_vocabulary),
        )
        .route("/api/admin/questions", post(create_question))
        .route(
            "/api/admin/questions/{id}",
            axum::routing::put(update_question).delete(delete_question),
        )
        .route("/example", post(find_example))
        .route("/reddit", post(find_example))
        .route("/pronunciation", post(pronunciation))
        .route("/practice/sentence", post(practice_sentence))
        .route("/practice/evaluate", post(practice_evaluate))
        .with_state(state);
    let address = SocketAddr::from(([0, 0, 0, 0], port));
    println!("Language Learning helper listening on http://{address}");
    let listener = tokio::net::TcpListener::bind(address)
        .await
        .expect("could not bind server");
    axum::serve(listener, app).await.expect("server failed");
}

async fn practice_sentence(
    State(state): State<AppState>,
    Json(request): Json<PracticeSentenceRequest>,
) -> Result<Json<PracticeSentence>, (StatusCode, String)> {
    if request.term.chars().count() > 250 || request.region.len() > 100 {
        return Err((StatusCode::BAD_REQUEST, "Invalid practice request".into()));
    }
    let key = format!("{}|{}|{}", request.language, request.region, request.term).to_lowercase();
    if let Some(found) = state.practice_cache.read().await.get(&key).cloned() {
        return Ok(Json(found));
    }
    let prompt = format!(
        "Create one short, natural beginner-friendly sentence in {language} as spoken in {region}, using this word or concept: {term}. Return only JSON exactly like {{\"sentence\":\"...\",\"translation\":\"English...\"}}.",
        language = request.language,
        region = request.region,
        term = request.term
    );
    let response = state
        .client
        .post("https://api.openai.com/v1/responses")
        .bearer_auth(&state.api_key)
        .json(&json!({"model":state.model,"store":false,"input":prompt,"max_output_tokens":120}))
        .send()
        .await
        .map_err(internal_error)?;
    let status = response.status();
    let body: Value = response.json().await.map_err(internal_error)?;
    if !status.is_success() {
        return Err(openai_upstream_error("sentence", status, &body));
    }
    let text = extract_output_text(&body)
        .ok_or((StatusCode::BAD_GATEWAY, "No sentence was generated".into()))?;
    let cleaned = text
        .trim()
        .trim_start_matches("```json")
        .trim_start_matches("```")
        .trim_end_matches("```")
        .trim();
    let sentence: PracticeSentence = serde_json::from_str(cleaned).map_err(internal_error)?;
    state
        .practice_cache
        .write()
        .await
        .insert(key, sentence.clone());
    Ok(Json(sentence))
}

async fn practice_evaluate(
    State(state): State<AppState>,
    Json(request): Json<PracticeEvaluationRequest>,
) -> Result<Json<Value>, (StatusCode, String)> {
    if request.audio_base64.len() > 2_000_000 || request.sentence.chars().count() > 400 {
        return Err((StatusCode::BAD_REQUEST, "Recording is too large".into()));
    }
    let number = state.practice_evaluations.fetch_add(1, Ordering::Relaxed);
    if number >= state.max_practice_evaluations {
        state.practice_evaluations.fetch_sub(1, Ordering::Relaxed);
        return Err((
            StatusCode::TOO_MANY_REQUESTS,
            "Practice evaluation limit reached".into(),
        ));
    }
    let instruction = format!(
        "Listen to this learner reading the target sentence in {language}, using the {region} variety. Target: {sentence}\nGive encouraging, concise pronunciation coaching. Mention what was clear, then at most two specific sounds, stress, or rhythm improvements. Account for the selected regional variety; do not penalize a valid regional accent. Include a cautious score out of 100. Do not claim phonetic precision beyond what the audio supports.",
        language = request.language,
        region = request.region,
        sentence = request.sentence
    );
    let response=state.client.post("https://api.openai.com/v1/chat/completions").bearer_auth(&state.api_key)
        .json(&json!({"model":state.audio_model,"messages":[{"role":"user","content":[{"type":"text","text":instruction},{"type":"input_audio","input_audio":{"data":request.audio_base64,"format":"wav"}}]}],"modalities":["text"],"max_tokens":220}))
        .send().await.map_err(internal_error)?;
    let status = response.status();
    let body: Value = response.json().await.map_err(internal_error)?;
    if !status.is_success() {
        return Err(openai_upstream_error("audio evaluation", status, &body));
    }
    let feedback = body
        .pointer("/choices/0/message/content")
        .and_then(Value::as_str)
        .ok_or((
            StatusCode::BAD_GATEWAY,
            "No pronunciation feedback was returned".into(),
        ))?;
    Ok(Json(json!({"feedback":feedback})))
}

async fn quiz_data(State(state): State<AppState>) -> Result<Json<Value>, (StatusCode, String)> {
    database::export(&state.database)
        .await
        .map(Json)
        .map_err(internal_error)
}

async fn admin_page() -> Html<&'static str> {
    Html(include_str!("admin.html"))
}

async fn home_page() -> Html<&'static str> {
    Html(include_str!("home.html"))
}

async fn privacy_page() -> Html<&'static str> {
    Html(include_str!("privacy.html"))
}

async fn terms_page() -> Html<&'static str> {
    Html(include_str!("terms.html"))
}

fn authorize(headers: &HeaderMap, state: &AppState) -> Result<(), (StatusCode, String)> {
    let supplied = headers
        .get("x-admin-token")
        .and_then(|value| value.to_str().ok())
        .unwrap_or("");
    if !state.admin_token.is_empty() && supplied == state.admin_token {
        Ok(())
    } else {
        Err((
            StatusCode::UNAUTHORIZED,
            "Invalid or missing admin token".into(),
        ))
    }
}

async fn admin_word_bank(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<Value>, (StatusCode, String)> {
    authorize(&headers, &state)?;
    database::export(&state.database)
        .await
        .map(Json)
        .map_err(internal_error)
}

fn clean_optional(value: Option<String>) -> Option<String> {
    value
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
}

async fn create_vocabulary(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(input): Json<VocabularyInput>,
) -> Result<Json<Value>, (StatusCode, String)> {
    authorize(&headers, &state)?;
    let result = sqlx::query(
        "INSERT INTO vocabulary(language,term,translation,article,noun,difficulty,variant,spoken_language) VALUES(?,?,?,?,?,?,?,?)",
    )
    .bind(input.language)
    .bind(input.term.trim())
    .bind(input.translation.trim())
    .bind(clean_optional(input.article))
    .bind(clean_optional(input.noun))
    .bind(input.difficulty)
    .bind(clean_optional(input.variant))
    .bind(clean_optional(input.spoken_language))
    .execute(&state.database)
    .await
    .map_err(internal_error)?;
    Ok(Json(json!({"id":result.last_insert_rowid()})))
}

async fn update_vocabulary(
    State(state): State<AppState>,
    AxumPath(id): AxumPath<i64>,
    headers: HeaderMap,
    Json(input): Json<VocabularyInput>,
) -> Result<Json<Value>, (StatusCode, String)> {
    authorize(&headers, &state)?;
    sqlx::query(
        "UPDATE vocabulary SET language=?,term=?,translation=?,article=?,noun=?,difficulty=?,variant=?,spoken_language=? WHERE id=?",
    )
    .bind(input.language)
    .bind(input.term.trim())
    .bind(input.translation.trim())
    .bind(clean_optional(input.article))
    .bind(clean_optional(input.noun))
    .bind(input.difficulty)
    .bind(clean_optional(input.variant))
    .bind(clean_optional(input.spoken_language))
    .bind(id)
    .execute(&state.database)
    .await
    .map_err(internal_error)?;
    Ok(Json(json!({"ok":true})))
}

async fn delete_vocabulary(
    State(state): State<AppState>,
    AxumPath(id): AxumPath<i64>,
    headers: HeaderMap,
) -> Result<Json<Value>, (StatusCode, String)> {
    authorize(&headers, &state)?;
    sqlx::query("DELETE FROM vocabulary WHERE id=?")
        .bind(id)
        .execute(&state.database)
        .await
        .map_err(internal_error)?;
    Ok(Json(json!({"ok":true})))
}

async fn create_question(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(input): Json<QuestionInput>,
) -> Result<Json<Value>, (StatusCode, String)> {
    authorize(&headers, &state)?;
    let result = sqlx::query("INSERT INTO questions(language,category,prompt,answer,explanation,translation,hints_json,spoken_text,difficulty) VALUES(?,?,?,?,?,?,?,?,?)")
        .bind(input.language).bind(input.category).bind(input.prompt.trim()).bind(input.answer.trim())
        .bind(input.explanation.trim()).bind(clean_optional(input.translation))
        .bind(serde_json::to_string(&input.hints).unwrap_or_else(|_| "[]".into())).bind(clean_optional(input.spoken_text)).bind(input.difficulty)
        .execute(&state.database).await.map_err(internal_error)?;
    Ok(Json(json!({"id":result.last_insert_rowid()})))
}

async fn update_question(
    State(state): State<AppState>,
    AxumPath(id): AxumPath<i64>,
    headers: HeaderMap,
    Json(input): Json<QuestionInput>,
) -> Result<Json<Value>, (StatusCode, String)> {
    authorize(&headers, &state)?;
    sqlx::query("UPDATE questions SET language=?,category=?,prompt=?,answer=?,explanation=?,translation=?,hints_json=?,spoken_text=?,difficulty=? WHERE id=?")
        .bind(input.language).bind(input.category).bind(input.prompt.trim()).bind(input.answer.trim())
        .bind(input.explanation.trim()).bind(clean_optional(input.translation))
        .bind(serde_json::to_string(&input.hints).unwrap_or_else(|_| "[]".into())).bind(clean_optional(input.spoken_text)).bind(input.difficulty).bind(id)
        .execute(&state.database).await.map_err(internal_error)?;
    Ok(Json(json!({"ok":true})))
}

async fn delete_question(
    State(state): State<AppState>,
    AxumPath(id): AxumPath<i64>,
    headers: HeaderMap,
) -> Result<Json<Value>, (StatusCode, String)> {
    authorize(&headers, &state)?;
    sqlx::query("DELETE FROM questions WHERE id=?")
        .bind(id)
        .execute(&state.database)
        .await
        .map_err(internal_error)?;
    Ok(Json(json!({"ok":true})))
}

async fn pronunciation(
    State(state): State<AppState>,
    Json(request): Json<SpeechRequest>,
) -> Result<Response<Body>, (StatusCode, String)> {
    let text = request.text.trim();
    if text.is_empty()
        || text.chars().count() > 250
        || request.language.len() > 50
        || request.region.len() > 100
    {
        return Err((StatusCode::BAD_REQUEST, "Invalid pronunciation text".into()));
    }
    let key = format!("{}|{}|{}", request.language, request.region, text).to_lowercase();
    if let Some(audio) = state.speech_cache.read().await.get(&key).cloned() {
        return audio_response(audio);
    }
    let request_number = state
        .uncached_speech_requests
        .fetch_add(1, Ordering::Relaxed);
    if request_number >= state.max_uncached_speech_requests {
        state
            .uncached_speech_requests
            .fetch_sub(1, Ordering::Relaxed);
        return Err((
            StatusCode::TOO_MANY_REQUESTS,
            "Speech generation limit reached".into(),
        ));
    }
    let response = state.client
        .post("https://api.openai.com/v1/audio/speech")
        .bearer_auth(&state.api_key)
        .json(&json!({
            "model": state.tts_model,
            "voice": "coral",
            "input": text,
            "instructions": format!(
                "Pronounce this naturally and clearly in {} as spoken in {}. Speak only the supplied text.",
                request.language, request.region
            ),
            "response_format": "mp3"
        }))
        .send().await.map_err(internal_error)?;
    let status = response.status();
    if !status.is_success() {
        let body: Value = response.json().await.unwrap_or_else(|_| json!({}));
        return Err(openai_upstream_error("speech", status, &body));
    }
    let audio = response.bytes().await.map_err(internal_error)?.to_vec();
    state.speech_cache.write().await.insert(key, audio.clone());
    audio_response(audio)
}

fn audio_response(audio: Vec<u8>) -> Result<Response<Body>, (StatusCode, String)> {
    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "audio/mpeg")
        .header(header::CACHE_CONTROL, "public, max-age=86400")
        .body(Body::from(audio))
        .map_err(internal_error)
}

async fn find_example(
    State(state): State<AppState>,
    Json(request): Json<FindRequest>,
) -> Result<Json<ExampleResult>, (StatusCode, String)> {
    if request.term.len() > 500 || request.answer.len() > 200 || request.language.len() > 50 {
        return Err((StatusCode::BAD_REQUEST, "Request is too long".into()));
    }
    let source = SearchSource::from_request(&request.source, &request.language)?;
    let key = format!(
        "{}|{}|{}|{}|explicit:{}",
        request.language, request.term, request.answer, source.id, request.allow_explicit
    )
    .to_lowercase();
    if let Some(found) = state.cache.read().await.get(&key).cloned() {
        return Ok(Json(found));
    }
    let request_number = state.uncached_requests.fetch_add(1, Ordering::Relaxed);
    if request_number >= state.max_uncached_requests {
        state.uncached_requests.fetch_sub(1, Ordering::Relaxed);
        return Err((
            StatusCode::TOO_MANY_REQUESTS,
            "Lookup limit reached; restart the helper to reset it".into(),
        ));
    }

    let content_instruction = if request.allow_explicit {
        "Content marked explicit or sensitive is acceptable, but accurately flag it when the source does so."
    } else {
        "Exclude sexually explicit, graphically violent, and source-marked NSFW content."
    };
    let prompt = format!(
        "Find one interesting public {source_name} item genuinely related to this {language} language-learning word or concept. Prefer a real-world use of the learned language that is memorable and understandable in context. Return a short English description and cite the exact item. Do not cite a search page, account/profile page, or a different website. Never invent a result. {content_instruction}\n\nLearning word or question: {term}\nEnglish meaning or answer: {answer}",
        source_name = source.label,
        language = request.language,
        term = request.term,
        answer = request.answer,
        content_instruction = content_instruction
    );
    let response = state
        .client
        .post("https://api.openai.com/v1/responses")
        .bearer_auth(&state.api_key)
        .json(&json!({
            "model": state.model,
            "store": false,
            "tools": [{"type": "web_search", "filters": {"allowed_domains": source.domains}}],
            "input": prompt,
            "max_output_tokens": 220
        }))
        .send()
        .await
        .map_err(internal_error)?;
    let status = response.status();
    let body: Value = response.json().await.map_err(internal_error)?;
    if !status.is_success() {
        return Err(openai_upstream_error("example search", status, &body));
    }
    let (summary, title, url) = extract_result(&body, &source.domains).ok_or_else(|| {
        eprintln!(
            "example search ({}) returned no usable citation for “{}”",
            source.id, request.term
        );
        (
            StatusCode::NOT_FOUND,
            "No matching public example was found".into(),
        )
    })?;
    let nsfw = if source.id == "reddit" {
        reddit_is_nsfw(&state.client, &url).await.unwrap_or(false)
    } else {
        false
    };
    if nsfw && !request.allow_explicit {
        return Err((
            StatusCode::NOT_FOUND,
            "The result was marked NSFW and explicit content is blocked".into(),
        ));
    }
    let found = ExampleResult {
        title,
        url,
        summary,
        nsfw,
        source: source.label.into(),
    };
    state.cache.write().await.insert(key, found.clone());
    Ok(Json(found))
}

async fn reddit_is_nsfw(client: &Client, thread_url: &str) -> Option<bool> {
    let mut url = Url::parse(thread_url).ok()?;
    url.set_query(None);
    url.set_fragment(None);
    let json_url = format!("{}.json?raw_json=1", url.as_str().trim_end_matches('/'));
    let body: Value = client
        .get(json_url)
        .header(
            "User-Agent",
            "LanguageLearning/0.1 (personal language quiz)",
        )
        .send()
        .await
        .ok()?
        .json()
        .await
        .ok()?;
    body.pointer("/0/data/children/0/data/over_18")
        .and_then(Value::as_bool)
}

fn extract_output_text(body: &Value) -> Option<String> {
    for output in body.get("output")?.as_array()? {
        for content in output
            .get("content")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
        {
            if let Some(text) = content.get("text").and_then(Value::as_str) {
                return Some(text.to_string());
            }
        }
    }
    None
}

fn extract_result(body: &Value, allowed_domains: &[&str]) -> Option<(String, String, String)> {
    for output in body.get("output")?.as_array()? {
        for content in output
            .get("content")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
        {
            let text = content
                .get("text")
                .and_then(Value::as_str)
                .unwrap_or("")
                .trim()
                .to_string();
            for annotation in content
                .get("annotations")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
            {
                let citation = annotation.get("url_citation").unwrap_or(annotation);
                let raw_url = citation.get("url").and_then(Value::as_str)?;
                let parsed = Url::parse(raw_url).ok()?;
                let host = parsed.host_str().unwrap_or_default();
                if allowed_domains
                    .iter()
                    .any(|domain| host == *domain || host.ends_with(&format!(".{domain}")))
                {
                    let title = citation
                        .get("title")
                        .and_then(Value::as_str)
                        .filter(|title| !title.trim().is_empty())
                        .unwrap_or("A related real-world example")
                        .to_string();
                    return Some((text, title, raw_url.to_string()));
                }
            }
        }
    }
    None
}

struct SearchSource {
    id: &'static str,
    label: &'static str,
    domains: Vec<&'static str>,
}

impl SearchSource {
    fn from_request(value: &str, language: &str) -> Result<Self, (StatusCode, String)> {
        let source = match value.trim().to_lowercase().as_str() {
            "reddit" => Self {
                id: "reddit",
                label: "Reddit",
                domains: vec!["reddit.com"],
            },
            "bluesky" => Self {
                id: "bluesky",
                label: "Bluesky",
                domains: vec!["bsky.app"],
            },
            "gutefrage" if language.eq_ignore_ascii_case("German") => Self {
                id: "gutefrage",
                label: "gutefrage",
                domains: vec!["gutefrage.net"],
            },
            "jeuxvideo" if language.eq_ignore_ascii_case("French") => Self {
                id: "jeuxvideo",
                label: "Jeuxvideo.com forums",
                domains: vec!["jeuxvideo.com"],
            },
            "der_spiegel" if language.eq_ignore_ascii_case("German") => Self {
                id: "der_spiegel",
                label: "Der Spiegel",
                domains: vec!["spiegel.de"],
            },
            "radio_canada" if language.eq_ignore_ascii_case("French") => Self {
                id: "radio_canada",
                label: "Radio-Canada (CBC French)",
                domains: vec!["ici.radio-canada.ca"],
            },
            "le_monde" if language.eq_ignore_ascii_case("French") => Self {
                id: "le_monde",
                label: "Le Monde",
                domains: vec!["lemonde.fr"],
            },
            "der_spiegel" | "radio_canada" | "le_monde" | "gutefrage" | "jeuxvideo" => {
                return Err((
                    StatusCode::BAD_REQUEST,
                    "That source is not available for the selected language".into(),
                ));
            }
            _ => return Err((StatusCode::BAD_REQUEST, "Unknown example source".into())),
        };
        Ok(source)
    }
}

fn internal_error(error: impl std::fmt::Display) -> (StatusCode, String) {
    eprintln!("{error}");
    (
        StatusCode::BAD_GATEWAY,
        "The lookup service is temporarily unavailable".into(),
    )
}

fn openai_upstream_error(feature: &str, status: reqwest::StatusCode, body: &Value) -> (StatusCode, String) {
    let detail = body
        .pointer("/error/message")
        .and_then(Value::as_str)
        .or_else(|| body.get("error").and_then(Value::as_str))
        .unwrap_or("(no error message)");
    eprintln!("OpenAI {feature} failed: HTTP {status} — {detail}");
    (
        StatusCode::BAD_GATEWAY,
        format!("OpenAI {feature} request failed: {status}"),
    )
}
