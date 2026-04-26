# Relevant Priors API

A small Java HTTP service for the `relevant-priors-v1` challenge. It accepts the challenge JSON at `POST /predict` and returns one Boolean prediction for every prior study in every case.

**Headline numbers (5-fold grouped cross-validation on the 996-case public split, 27,614 priors):**

| Model | Accuracy | Precision | Recall | F1 |
|------|---------:|---------:|-------:|----:|
| Always `false` baseline                          | 0.7622 | – | – | – |
| Strong rule-based overrides only                 | 0.9335 | – | – | – |
| **Final SGD logistic regression + tag features** | **0.9543** | 0.9081 | 0.8987 | 0.9034 |

These are honest hold-out numbers: cases are grouped by `case_id`, so all priors of a single case live in either the training or the test fold (never both). Re-run with `java -cp out Main --evaluate 5`.

## Why Java works here

The challenge only requires a public HTTP endpoint with the required request/response schema. The implementation language does not matter as long as the endpoint is reachable, returns the right JSON shape, and stays within the 360-second evaluator timeout.

This version uses only the Java standard library:

- `com.sun.net.httpserver.HttpServer` for HTTP
- a small included JSON parser/writer (`Main.Json`)
- no Maven, Gradle, Spring, external APIs, or LLM calls

That keeps the submission easy to inspect, fast to build, and reliable in Docker.

## Run locally

Requirements: JDK 21 (we tested with Eclipse Temurin 21).

```bash
javac -encoding UTF-8 -d out src/Main.java
java -cp out Main
```

Default `HOST=0.0.0.0` and `PORT=8000`; both can be overridden via env vars.

### Test the predictor without starting the server

```bash
java -cp out Main --predict-file tests/example_request.json
```

### Test the live HTTP endpoint

```bash
curl -s http://localhost:8000/health
curl -s -X POST http://localhost:8000/predict \
  -H 'Content-Type: application/json' \
  -H 'X-Request-Id: smoke-1' \
  --data @tests/example_request.json
```

### Re-run the offline evaluation

```bash
# 5-fold grouped cross-validation; prints per-fold and pooled metrics.
java -cp out Main --evaluate 5

# Inspect false positives and false negatives on the held-out fold 0.
java -cp out Main --show-errors 50

# Inspect tags / overrides / features for a single study pair.
java -cp out Main --debug-pair "MRI BRAIN" "CT HEAD"
```

## Docker

```bash
docker build -t relevant-priors-java .
docker run --rm -p 8000:8000 relevant-priors-java
```

The container starts the HTTP server on port `8000`, trains the model from `data/relevant_priors_public.json` (about 30–60 seconds depending on hardware), and is then ready to serve `POST /predict`.

### Deploy on Render

Render can run this service directly from the included `Dockerfile`.

1. Push this project to GitHub.
2. In Render, click **New +** → **Web Service**.
3. Connect your GitHub repository.
4. Use these settings:
   - **Runtime:** Docker
   - **Root Directory:** leave blank if `Dockerfile` is at the repository root; set it to `ashby_submission` if your GitHub repo contains this project inside an outer folder.
   - **Branch:** your deployment branch, usually `main`.
   - **Health Check Path:** `/health`
   - **Instance Type:** Free can work, but startup may take 30–60 seconds while the model trains. A paid small instance is safer for the challenge evaluator.
5. Leave `PORT` unset in Render. Render provides its own `PORT`, and the app reads it automatically.
6. Optional environment variables:
   - `HOST=0.0.0.0`
   - `JAVA_OPTS=-Xms256m -Xmx512m`
7. Click **Create Web Service**.
8. Wait for the deploy logs to show the Java process starting and the `/health` check passing.

After Render finishes deploying, open:

```text
https://<your-render-service>.onrender.com/health
```

You should see JSON like:

```json
{"ok":true,"model_loaded":true,"trained_examples":27614}
```

Do not worry if the plain homepage URL returns `404 Not Found`. This API does not serve a homepage; it only serves the endpoints below.

The endpoint URL you submit to the challenge is:

```text
https://<your-render-service>.onrender.com/predict
```

For this deployed service, use:

```text
https://ashby-submisson.onrender.com/predict
```

Opening `/predict` directly in a browser sends a `GET` request, so it will show:

```json
{"error":"method_not_allowed"}
```

That is expected. The challenge evaluator will send a `POST` request with JSON. To test the deployed endpoint yourself:

```bash
curl -s -X POST https://ashby-submisson.onrender.com/predict \
  -H 'Content-Type: application/json' \
  -H 'X-Request-Id: render-smoke-1' \
  --data @tests/example_request.json
```

### Fly.io

This repo includes `fly.toml` with `primary_region = "ord"` (Chicago). That fixes the error `region  not found`, which happens when Fly has no region.

```bash
flyctl launch --no-deploy --primary-region ord
flyctl deploy
```

If you still see `region  not found`, the CLI did not pick up a region. Use `--primary-region ord` or `-r ord` (same as `--region ord` on `fly launch`). List codes: `flyctl platform regions`.

**Note:** `[http_service]` must not list `processes = ["app"]` unless you also define a `[processes]` block. A stray `processes` line can trigger this error.

## API contract

### Request (POST /predict)

```jsonc
{
  "cases": [
    {
      "case_id": "1001016",
      "current_study": {
        "study_id": "3100042",
        "study_description": "MRI BRAIN STROKE LIMITED WITHOUT CONTRAST",
        "study_date": "2026-03-08"
      },
      "prior_studies": [
        {"study_id": "2453245", "study_description": "MRI BRAIN STROKE LIMITED WITHOUT CONTRAST", "study_date": "2020-03-08"},
        {"study_id": "992654",  "study_description": "CT HEAD WITHOUT CNTRST",                  "study_date": "2021-03-08"}
      ]
    }
  ]
}
```

### Response

```jsonc
{
  "predictions": [
    {"case_id": "1001016", "study_id": "2453245", "predicted_is_relevant": true},
    {"case_id": "1001016", "study_id": "992654",  "predicted_is_relevant": true}
  ]
}
```

The service always returns one prediction per prior study in the request, never skips, and is bulk-friendly (the 360 s evaluator timeout is comfortable: a typical case with 25 priors completes in single-digit milliseconds after the model is warm).

### Other endpoints

- `GET /health` returns `{"ok": true, "model_loaded": true, "trained_examples": 27614}` once training finishes.
- `405 method_not_allowed` if you POST to `/health` or GET `/predict`.
- `400 invalid_json` / `400 empty_body` / `400 expected_json_object` for bad input.
- `500 prediction_failed` (logged with the request id) for unexpected failures.

Logs are line-oriented (JUL) and include `predict_ok id=<X-Request-Id> cases=N predictions=N elapsed_ms=…` so you can correlate evaluator traffic with server logs.

## Method (high level)

The service trains a single L2-regularized logistic regression at startup from the public labeled examples:

1. Normalize current/prior `study_description` text (lowercase, split punctuation, English plurals).
2. Tag each study with one or more **modality** tokens (`MOD_CT`, `MOD_MR`, `MOD_US`, `MOD_NM`, `MOD_XR`, `MOD_MG`, `MOD_FL`, `MOD_DXA`, `MOD_IR`, `MOD_WHOLEBODY_NM`) and one or more **anatomy / region** tokens (`REG_BRAIN_HEAD`, `REG_CHEST`, `REG_ABDOMEN_PELVIS`, `REG_BREAST`, sub-regions of spine and MSK, etc.).
3. Build pair features for the (current, prior) pair: shared tags, exact vs any modality/region match, cross-modality pairs, MSK sub-region interactions, laterality (LT/RT/BIL), procedural-vs-diagnostic flags, shared word and bigram tokens, coarse stem tokens for abbreviation matching, date-gap bucket, and a handful of clinical "follow-up" flags.
4. Add two boolean features that summarize the rule-based "definitely relevant" / "definitely not relevant" overrides; the model is free to weight them as it sees fit instead of having them rigidly enforced at inference time.
5. Train logistic regression with SGD (40 epochs, lr 0.2 with 1/(1+0.5·epoch) decay, L2 = 3e-4, weights warm-started from a Naive-Bayes log-odds estimate, weights clipped to ±6).
6. Pick a single decision threshold by averaging optimal thresholds over three deterministic inner cross-validation splits.

At inference the model owns the decision: there are no hard rules in the prediction path, which makes the service auditable from a single `predictPair(...)` call.

The model intentionally ignores patient names and patient IDs and never uses an exact `(case_id, study_id) -> label` lookup. That avoids public-set memorization and makes the public/private gap small.

## File layout

```
.
├── Dockerfile
├── .dockerignore
├── README.md
├── experiments.md
├── data/
│   └── relevant_priors_public.json     # labeled training data
├── src/
│   └── Main.java                       # full service + model + JSON
└── tests/
    └── example_request.json            # sample evaluator-shaped request
```
