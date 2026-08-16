# Literary Oracle

[Live demo](https://literary-oracle-4qxy.onrender.com)

**440 passages · 39 authors · 19 languages**

Literary Oracle lets a piece of literature find you across time. Write what has been on your mind, choose how closely the draw should follow your words, and select **Draw from the archive**. An archive drawer opens while the app searches, then a literary card appears.

The front of the card shows the selected passage in the detected input language, or in any of the 19 languages chosen manually, together with its author, work, and genre. Select the card to turn it over. The reverse shows the original passage and language, direct source, context, author biography, public-domain status, and translation note. Below the card, the draw record shows the original language, chance level, and the number of passages opened for that draw.

The interface uses muted green, paper grey, burgundy, and traditional serif type to give the archive, cards, saved collection, and share image one consistent vintage identity.

## Features

- 440 passages from 39 authors, with direct source links and structured metadata
- Automatic input-language detection and manual selection across 19 display languages
- A relevance-to-chance slider with a visible chance level and candidate-pool size
- Reversible literary cards containing the translation, original text, attribution, context, source, and copyright information
- A **Save passage** action stored in the browser; **Saved passages** in the upper-right corner opens the saved-passages list page
- A **Share card** action that creates a matching PNG with the selected translation, original passage, author, work, year, original language, and source label, while leaving out the user's input
- Keyboard navigation, visible focus states, screen-reader labels, reduced-motion support, responsive layouts, and RTL handling for Arabic
- A safety check before literary selection: immediate self-harm risk opens a support view with emergency guidance and a helpline link, while concerning or ambiguous wording asks the user to confirm that they are currently safe before continuing. (Many literary expressions themselves arise from or express states of distress, so blocking all of them would interfere with drawing the relevant passages.)

## How selection works

1. The app detects the input language and uses it for the displayed translation. The user can override this choice and select any of the 19 supported languages.
2. Local context rules and the Azure AI Content Safety API assess self-harm risk before a passage is selected.
3. Through the Jina AI API, `jina-embeddings-v3` compares the input with the archive's pre-generated 1,024-dimensional vectors. The nearest 30 passages are then reordered by `jina-reranker-v3` using the input, translated passage, and English context.
4. At the **Closer to my words** end of the slider, the final draw uses the top 3 candidates and scores them with 95% semantic relevance and 5% randomness.
5. At the **Leave more to chance** end, the candidate pool expands to the top 25 and the score changes to 20% semantic relevance and 80% randomness.
6. Intermediate slider positions gradually change both the candidate-pool size and the balance between semantic relevance and randomness.
7. If the Jina API is unavailable, the app uses local theme and text matching as a fallback.

The main endpoint is `POST /api/oracle`. Runtime semantic status is available at `GET /api/semantic/status`.

## Tech stack

- Java 17 and Spring Boot 4.1.0
- HTML, CSS, and vanilla JavaScript
- Jina AI API for embeddings and reranking
- Azure AI Content Safety API
- Lingua 1.2.2 for language detection
- Docker and Render

## Archive and privacy

The archive is limited to original works verified and documented as public domain in both the United Kingdom and the United States as of 2026. The creator manually curated the author list; AI assisted with selecting passages for each author and recording their direct sources and public-domain information.

<details>
<summary><strong>Authors in the archive (39)</strong></summary>

1. Emily Dickinson
2. Rabindranath Tagore
3. Rainer Maria Rilke
4. Misuzu Kaneko
5. Heinrich Heine
6. Alexander Pushkin
7. Fernando Pessoa
8. Percy Bysshe Shelley
9. William Shakespeare
10. Fyodor Dostoevsky
11. Walt Whitman
12. Ralph Waldo Emerson
13. Li Bai（李白）
14. Matsuo Bashō（松尾芭蕉）
15. Friedrich Nietzsche
16. Edith Södergran
17. Charles Baudelaire
18. Edward Thomas
19. Anton Chekhov
20. Virginia Woolf
21. Kahlil Gibran
22. Federico García Lorca
23. Henry Wadsworth Longfellow
24. César Vallejo
25. Rubén Darío
26. Marina Tsvetaeva
27. Giosuè Carducci
28. Kobayashi Issa
29. Akiko Yosano
30. Li Qingzhao（李清照）
31. Su Shi（苏轼）
32. Rumi
33. Olaudah Equiano
34. Yun Dong-ju
35. Sappho
36. Horace
37. Al-Ma'arri
38. Nguyễn Du
39. Henry Lawson

</details>

Archive records are stored in `src/main/resources/archive.json`. Every record contains the original passage, language, author, work, year, type, themes, English context, direct source URL, public-domain status, and all 19 project translations. Project translations are marked as machine-assisted and are not attributed to published translators.

The application does not persist user input. Input may be sent to Jina AI for semantic matching and to Azure AI Content Safety for a safety check. Saved passages and the latest ten result IDs stay in the browser's local storage. Share cards are generated in the browser and never contain the user's input.

## Run locally

Requirements: Java 17. API keys are optional; the local fallback remains available without them.

```powershell
.\mvnw.cmd spring-boot:run
```

On macOS or Linux:

```bash
./mvnw spring-boot:run
```

Open `http://localhost:8080`.

Optional environment variables:

| Variable | Used for |
| --- | --- |
| `JINA_API_KEY` | Jina embeddings and reranking |
| `CONTENT_SAFETY_ENDPOINT` | Azure AI Content Safety endpoint |
| `CONTENT_SAFETY_KEY` | Azure AI Content Safety key |

## Tests

```powershell
.\mvnw.cmd test
```

The current suite contains 240 tests covering the archive, embeddings, semantic retrieval, language detection, safety handling, API responses, selection logic, and frontend contracts. The latest included test reports show 240 passed, with 0 failures and 0 errors.

## Possible next steps

- Add more verified passages for authors with lighter coverage
- Expand representation across regions while keeping source and public-domain checks
- Localize the full interface in all supported languages
- Add a world map for browsing authors by place
