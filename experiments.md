# Experiments

## Goal

We predict whether each prior exam should be shown while the radiologist reads the current exam. The API must return one answer per prior. Missing answers count as wrong.

## Data

The public file has 996 cases and 27,614 labeled priors.

| Quantity | Count | Share |
|---|---:|---:|
| Total labeled priors | 27,614 | 100.0% |
| Relevant (positive)  | 6,567  | 23.78% |
| Not relevant         | 21,047 | 76.22% |

Most priors are not relevant. A model that always says `false` gets 76.22% accuracy. Our model must do better than that.

## How we measured accuracy

We used 5-fold cross-validation on the public file. Priors are grouped by `case_id`. All priors from one case stay in the same fold. We never train on a case and test on the same case.

For each fold we:

1. Train on the other four folds.
2. Pick one decision threshold using three inner splits (also grouped by case).
3. Test on the held-out fold.

Run the same evaluation locally:

```bash
java -cp out Main --evaluate 5
```

## Results

| Variant | Accuracy | Precision | Recall | F1 |
|---|---:|---:|---:|---:|
| Always `false` | 0.7622 | – | – | – |
| Rules only (strong overrides) | 0.9335 | – | – | – |
| Naive Bayes log-odds + tags | ~0.9021 | ~0.84 | ~0.85 | ~0.84 |
| + Word-bounded tags, plurals, finer body regions | 0.9468 | 0.8718 | 0.9103 | 0.8906 |
| + Old rules as features (`OVR_REL` / `OVR_IRR`), not hard gates | 0.9502 | 0.8873 | 0.9056 | 0.8964 |
| + Short stem tokens for abbreviations | 0.9526 | 0.8951 | 0.9068 | 0.9009 |
| **Final: SGD logistic regression (40 epochs, L2 = 3e-4)** | **0.9543** | 0.9081 | 0.8987 | 0.9034 |

The last row matches the code in `src/Main.java`.

## What helped

**SGD logistic regression instead of Naive Bayes.** Many features move together (for example “ct” as a word, “ct” in a bigram, and `MOD_CT`). Naive Bayes treats them as independent. SGD does not. We also drop features that appear only once. Accuracy went up by about one to two points.

**Word-bounded matching for tags.** We had a bug: the text “complete” matched the alias “le” (lower extremity) because matching was substring-based. We fixed that with word boundaries and simple plural handling.

**Finer body regions.** One broad “MSK” tag made unrelated joints look related. We split MSK into shoulder, elbow, wrist, hip, knee, and so on. We split spine into cervical, thoracic, lumbar, and so on. We added laterality checks for breast and MSK where it mattered.

**Rules as features.** Hard rules sometimes disagreed with the labels. We kept the same signals as features `OVR_REL` and `OVR_IRR`. The model learns how strong they should be.

**Stem tokens.** Descriptions use many spellings for the same idea (`cntrst`, `contr`, `abd`, `abdom`). Shared five-character stems link current and prior text without a huge feature list.

**Stable threshold tuning.** A single random 20% holdout made the threshold jump around. We pool scores from three fixed inner splits and pick one threshold on the combined list.

**Logging.** We log request ids and timing. That helped when testing the HTTP API.

## What we did not do

We did not memorize answers by `(case_id, study_id)`. That would look good on the public set and fail on the private set.

We did not call an LLM per request. Large batches could time out.

We did not add big ML frameworks. The problem size fits a small Java model.

We did not hand-pick the threshold on the full public set. Thresholds come only from cross-validation splits.

## What we tried and dropped

- Three SGD models with different random seeds, then average: no gain, slower training.
- Extra features that mixed date, region, and modality: no gain.
- Class-frequency weights vs equal weights: about the same; we ship equal weights for a simpler threshold.

## Next steps

1. Save trained weights to a file at build time so the container starts faster.
2. Optional: one threshold per modality (CT, MR, XR, US, mammography).
3. Label a small set of hard cases where the model disagrees with experts.
4. Optional: calibrate scores to probabilities for UI only.
5. Grow the synonym list for rare phrases (for example enterography, very short procedure names).
