(() => {
  "use strict";

  const HISTORY_KEY = "literaryOracle.recentIds";
  const SAVED_STORAGE_KEY = "literaryOracle.saved.v1";
  const MINIMUM_RITUAL_MS = 1600;
  const FALLBACK_LANGUAGE = "en";
  const CONFIG_URL = "/config/supported-languages.json";
  const ARCHIVE_SUMMARY_URL = "/config/archive-summary.json";
  const ENGLISH_UI_URL = "/i18n/en.json";
  const CARD_DRAG_THRESHOLD_PX = 4;
  const SHARE_CARD_WIDTH = 1080;
  const SHARE_CARD_MIN_HEIGHT = 1350;
  const SHARE_CARD_MAX_HEIGHT = 1920;
  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
  const ENGLISH_LANGUAGE_NAMES = Object.freeze({
    en: "English",
    "zh-Hans": "Simplified Chinese",
    "zh-Hant": "Traditional Chinese",
    ja: "Japanese",
    ko: "Korean",
    es: "Spanish",
    fr: "French",
    de: "German",
    it: "Italian",
    pt: "Portuguese",
    ru: "Russian",
    sv: "Swedish",
    ar: "Arabic",
    hi: "Hindi",
    bn: "Bengali",
    id: "Indonesian",
    tr: "Turkish",
    vi: "Vietnamese",
    th: "Thai",
    lzh: "Literary Chinese",
    fa: "Persian",
    grc: "Ancient Greek",
    la: "Latin"
  });

  const views = {
    home: document.querySelector("#homeView"),
    ritual: document.querySelector("#ritualView"),
    result: document.querySelector("#resultView"),
    saved: document.querySelector("#savedView"),
    share: document.querySelector("#shareView"),
    safetyConfirmation: document.querySelector("#safetyConfirmationView"),
    crisis: document.querySelector("#crisisView")
  };

  const mainContent = document.querySelector("#mainContent");
  const form = document.querySelector("#oracleForm");
  const promptInput = document.querySelector("#prompt");
  const languageSelect = document.querySelector("#languageSelect");
  const languageStatus = document.querySelector("#languageStatus");
  const chanceInput = document.querySelector("#chance");
  const chanceValue = document.querySelector("#chanceValue");
  const chanceDescription = document.querySelector("#chanceDescription");
  const archiveScale = document.querySelector("#archiveScale");
  const drawButton = document.querySelector("#drawButton");
  const drawAgainButton = document.querySelector("#drawAgainButton");
  const formError = document.querySelector("#formError");
  const ritualStatus = document.querySelector("#ritualStatus");
  const archiveCard = document.querySelector("#archiveCard");
  const cardInner = document.querySelector("#cardInner");
  const cardFront = document.querySelector("#cardFront");
  const cardBack = document.querySelector("#cardBack");
  const cardStatus = document.querySelector("#cardStatus");
  const flipButton = document.querySelector("#flipButton");
  const savePassageButton = document.querySelector("#savePassageButton");
  const shareCardButton = document.querySelector("#shareCardButton");
  const savedPassagesButton = document.querySelector("#savedPassagesButton");
  const savedPassagesLabel = document.querySelector("#savedPassagesLabel");
  const savedPassagesSeparator = document.querySelector("#savedPassagesSeparator");
  const savedPassagesCount = document.querySelector("#savedPassagesCount");
  const savedViewCount = document.querySelector("#savedViewCount");
  const savedGrid = document.querySelector("#savedGrid");
  const savedEmptyState = document.querySelector("#savedEmptyState");
  const savedBackButton = document.querySelector("#savedBackButton");
  const sharePreparingStatus = document.querySelector("#sharePreparingStatus");
  const sharePreviewImage = document.querySelector("#sharePreviewImage");
  const shareError = document.querySelector("#shareError");
  const nativeShareButton = document.querySelector("#nativeShareButton");
  const downloadShareButton = document.querySelector("#downloadShareButton");
  const shareBackButton = document.querySelector("#shareBackButton");

  let activeRequest = null;
  let pendingSafetyRequest = null;
  let currentResult = null;
  let resultReturnView = "home";
  let savedPassages = [];
  let savedStorageError = "";
  let shareState = null;
  let shareGeneration = 0;
  let sharePreparing = false;
  let waitingTimer = null;
  let isDrawing = false;
  let supportedLanguages = [];
  let languageByCode = new Map();
  let englishTranslations = {};
  let browserLanguage = FALLBACK_LANGUAGE;
  let autoLanguage = FALLBACK_LANGUAGE;
  let autoLanguageCertain = true;
  let hasResolvedLanguage = false;
  let manualLanguage = "auto";
  let cardPointerOrigin = null;
  let cardPointerMoved = false;

  void initialize();

  async function initialize() {
    const [languageConfig, translations, archiveSummary] = await Promise.all([
      loadSupportedLanguages(),
      loadEnglishTranslation(),
      loadArchiveSummary()
    ]);

    supportedLanguages = languageConfig.selectable;
    languageByCode = new Map(languageConfig.all.map(language => [language.code, language]));
    englishTranslations = translations;
    browserLanguage = resolveBrowserLanguage(navigator.language || FALLBACK_LANGUAGE);
    autoLanguage = browserLanguage;
    savedPassages = readSavedPassages();

    localizeResultMetadata();
    updateArchiveScale(archiveSummary);
    populateLanguageSelect();
    bindEvents();
    refreshSavedUi();
    updateLanguageStatus();
    updateChanceReading();
    updateCardAccessibility(false, false);
    document.body.classList.remove("is-booting");
  }

  function bindEvents() {
    chanceInput.addEventListener("input", updateChanceReading);
    languageSelect.addEventListener("change", handleLanguageChange);
    promptInput.addEventListener("input", () => {
      if (manualLanguage === "auto") {
        hasResolvedLanguage = false;
        updateLanguageStatus();
      }
    });
    form.addEventListener("submit", handleSubmit);
    drawAgainButton.addEventListener("click", () => void beginDraw());
    savePassageButton.addEventListener("click", toggleSavedPassage);
    shareCardButton.addEventListener("click", () => {
      if (currentResult) void prepareShareCard(currentResult.data, "result", shareCardButton);
    });
    savedPassagesButton.addEventListener("click", handleSavedPassagesButton);
    savedBackButton.addEventListener("click", returnHome);
    nativeShareButton.addEventListener("click", () => void sharePreparedCard());
    downloadShareButton.addEventListener("click", downloadPreparedCard);
    shareBackButton.addEventListener("click", returnFromSharePreview);
    flipButton.addEventListener("click", () => toggleCard());
    archiveCard.addEventListener("pointerdown", handleCardPointerDown);
    archiveCard.addEventListener("pointermove", handleCardPointerMove);
    archiveCard.addEventListener("pointercancel", resetCardPointerGesture);
    archiveCard.addEventListener("click", handleCardClick);
    archiveCard.addEventListener("keydown", handleCardKeydown);
    document.addEventListener("keydown", handleDocumentKeydown);

    document.querySelector("#homeLink").addEventListener("click", event => {
      event.preventDefault();
      returnHome();
    });
    document.querySelector("#returnHomeButton").addEventListener("click", handleResultBack);
    document.querySelector("#crisisBackButton").addEventListener("click", returnHome);
    document.querySelector("#safetyNeedSupportButton").addEventListener("click", handleImmediateSupport);
    document.querySelector("#safetyContinueButton").addEventListener("click", handleSafetyContinue);
    window.addEventListener("storage", handleSavedStorageChange);
    window.addEventListener("resize", syncCardHeight);
    window.addEventListener("pagehide", resetSharePreview);

    if ("ResizeObserver" in window) {
      const cardObserver = new ResizeObserver(() => syncCardHeight());
      cardObserver.observe(cardFront);
      cardObserver.observe(cardBack);
    }
    document.fonts?.ready.then(syncCardHeight);
  }

  async function loadSupportedLanguages() {
    try {
      const response = await fetch(CONFIG_URL, { cache: "no-store" });
      if (!response.ok) throw new Error("language configuration unavailable");
      const config = await response.json();
      if (!Array.isArray(config.languages) || !config.languages.length) {
        throw new Error("invalid language configuration");
      }

      const normalize = language => ({
        code: String(language.code),
        nativeName: String(language.name),
        englishName: ENGLISH_LANGUAGE_NAMES[language.code] || String(language.name),
        direction: language.direction === "rtl" ? "rtl" : "ltr"
      });
      const selectable = config.languages.map(normalize);
      const originalOnly = Array.isArray(config.originalOnlyLanguages)
        ? config.originalOnlyLanguages.map(normalize)
        : [];
      return { selectable, all: [...selectable, ...originalOnly] };
    } catch {
      const english = {
        code: FALLBACK_LANGUAGE,
        nativeName: "English",
        englishName: "English",
        direction: "ltr"
      };
      return { selectable: [english], all: [english] };
    }
  }

  async function loadEnglishTranslation() {
    try {
      const response = await fetch(ENGLISH_UI_URL, { cache: "no-store" });
      if (!response.ok) throw new Error("English interface resource unavailable");
      return await response.json();
    } catch {
      return {};
    }
  }

  async function loadArchiveSummary() {
    try {
      const response = await fetch(ARCHIVE_SUMMARY_URL, { cache: "no-store" });
      if (!response.ok) throw new Error("archive summary unavailable");
      const summary = await response.json();
      const passages = Number(summary.passages);
      const authors = Number(summary.authors);
      if (!Number.isSafeInteger(passages) || passages < 1
          || !Number.isSafeInteger(authors) || authors < 1) {
        throw new Error("invalid archive summary");
      }
      return { passages, authors };
    } catch {
      return null;
    }
  }

  function updateArchiveScale(summary) {
    if (!summary || !archiveScale) return;
    archiveScale.textContent = `${summary.passages} passages · ${summary.authors} authors · ${supportedLanguages.length} languages`;
  }

  function populateLanguageSelect() {
    languageSelect.replaceChildren();
    const autoOption = document.createElement("option");
    autoOption.value = "auto";
    autoOption.textContent = "Based on input";
    languageSelect.append(autoOption);

    supportedLanguages.forEach(language => {
      const option = document.createElement("option");
      option.value = language.code;
      option.textContent = language.code === FALLBACK_LANGUAGE
        ? language.englishName
        : `${language.nativeName} · ${language.englishName}`;
      option.lang = "en";
      option.dir = "ltr";
      languageSelect.append(option);
    });
    languageSelect.value = manualLanguage;
  }

  function localizeResultMetadata() {
    setText("resultOriginalLanguageLabel",
      t("result.originalLanguageLabel", {}, "Original language"));
    setText("chanceLevelLabel", t("result.chanceLevelLabel", {}, "Chance level"));
    setText("archiveOpenedToLabel",
      t("result.archiveOpenedToLabel", {}, "Archive opened to"));
    document.querySelector("#drawExplanation").setAttribute("aria-label",
      t("result.detailsAriaLabel", {}, "Passage details"));
  }

  function handleLanguageChange() {
    manualLanguage = languageSelect.value;
    updateLanguageStatus();
  }

  function updateLanguageStatus() {
    if (manualLanguage !== "auto") {
      languageStatus.textContent = "";
      languageStatus.hidden = true;
      return;
    }

    languageStatus.hidden = false;
    if (!hasResolvedLanguage) {
      languageStatus.textContent = languageNameFor(browserLanguage);
      return;
    }

    const uncertainty = autoLanguageCertain ? "" : " · Not certain";
    languageStatus.textContent = `${languageNameFor(autoLanguage)}${uncertainty}`;
  }

  function handleSubmit(event) {
    event.preventDefault();
    void beginDraw();
  }

  async function beginDraw(requestPayload = null) {
    if (isDrawing) return;

    if (!requestPayload) {
      const text = promptInput.value.trim();
      if (!text) {
        showFormError(t("errors.required", {}, "Write something before drawing from the archive."));
        promptInput.setAttribute("aria-invalid", "true");
        promptInput.focus();
        return;
      }

      if (text.length > 4000) {
        showFormError(t("errors.tooLong", {}, "Keep the text to 4,000 characters or fewer."));
        promptInput.setAttribute("aria-invalid", "true");
        promptInput.focus();
        return;
      }

      pendingSafetyRequest = null;
      requestPayload = {
        text,
        chance: Number(chanceInput.value),
        excludedIds: readRecentIds(),
        language: manualLanguage,
        browserLanguage
      };
    }

    const controller = new AbortController();
    activeRequest = controller;
    isDrawing = true;
    clearFormError();
    setLoading(true);
    startRitual();

    const ritualStartedAt = performance.now();
    const minimumDuration = reducedMotion.matches ? 0 : MINIMUM_RITUAL_MS;
    waitingTimer = window.setTimeout(() => {
      views.ritual.classList.add("is-waiting");
      ritualStatus.textContent = "Loading…";
    }, minimumDuration || 1);

    try {
      const response = await fetch("/api/oracle", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(requestPayload),
        signal: controller.signal
      });

      const data = await readResponse(response);
      if (controller !== activeRequest) return;

      autoLanguage = normalizeSupportedCode(data.displayLanguage) || browserLanguage;
      autoLanguageCertain = data.languageCertain !== false;
      hasResolvedLanguage = true;
      updateLanguageStatus();

      if (data.crisis) {
        pendingSafetyRequest = null;
        renderCrisis(data);
        return;
      }
      if (data.safetyConfirmationRequired === true) {
        pendingSafetyRequest = { ...requestPayload };
        renderSafetyConfirmation();
        return;
      }
      if (typeof data.displayText !== "string" || !data.displayText.trim()) {
        throw new Error(t("errors.unreadable", {}, "The server returned an unreadable response. Try again."));
      }

      const elapsed = performance.now() - ritualStartedAt;
      await delay(Math.max(0, minimumDuration - elapsed));
      if (controller !== activeRequest) return;

      pendingSafetyRequest = null;
      renderResult(data);
      rememberResult(data.id);
    } catch (error) {
      if (error.name === "AbortError") return;
      pendingSafetyRequest = null;
      showView("home");
      showFormError(error instanceof TypeError
        ? t("errors.network", {}, "The network connection failed. Check the connection and try again.")
        : error.message || t("errors.api", {}, "The request could not be completed. Try again."));
      formError.focus();
    } finally {
      if (controller === activeRequest) {
        activeRequest = null;
        isDrawing = false;
        setLoading(false);
        mainContent.removeAttribute("aria-busy");
      }
      window.clearTimeout(waitingTimer);
      waitingTimer = null;
    }
  }

  async function readResponse(response) {
    let data;
    try {
      data = await response.json();
    } catch {
      throw new Error(t("errors.unreadable", {}, "The server returned an unreadable response. Try again."));
    }
    if (!response.ok) {
      throw new Error(t("errors.api", {}, "The request could not be completed. Try again."));
    }
    return data;
  }

  function startRitual() {
    ritualStatus.textContent = "Opening drawer…";
    views.ritual.classList.remove("is-running", "is-waiting");
    showView("ritual");
    mainContent.setAttribute("aria-busy", "true");
    void views.ritual.offsetWidth;
    views.ritual.classList.add("is-running");
  }

  function renderResult(data, options = {}) {
    const fromSaved = options.fromSaved === true;
    const displayLanguage = normalizeSupportedCode(data.displayLanguage) || FALLBACK_LANGUAGE;
    const originalLanguage = String(data.originalLanguage || "");
    const originalLanguageName = languageNameFor(originalLanguage);
    const displayTextValue = String(data.displayText);
    const canonicalAuthor = data.canonicalAuthor || data.author;
    const englishWorkTitle = data.englishWorkTitle;
    const localizedType = translateStableValue("types", data.type, "Literary passage");

    setText("typeStamp", localizedType);
    setText("displayLanguageLabel", data.displayLanguageEnglishName || languageNameFor(displayLanguage));
    setText("displayText", displayTextValue);
    setLanguageAttributes(document.querySelector("#displayText"), displayLanguage);
    setText("frontAuthor", canonicalAuthor, "Not yet verified");
    setText("frontWork", englishWorkTitle, "Not yet verified");

    setText("originalText", data.passageOriginal, "Not yet verified");
    setLanguageAttributes(document.querySelector("#originalText"), originalLanguage);
    setText("originalLanguageLabel", originalLanguageName);
    setText("originalWorkTitle", data.originalWorkTitle, "Not yet verified");
    setLanguageAttributes(document.querySelector("#originalWorkTitle"), originalLanguage);
    setText("originalAuthor", canonicalAuthor, "Not yet verified");
    setText("originalYear", displayValue(data.year));
    setText("originalType", localizedType);

    setText("contextNote", data.englishContextNote, "Not yet verified");
    setText("authorBio", data.englishAuthorBio, "Not yet verified");
    setText("translationNote", formatTranslationNote(data.englishTranslationNote), "Not yet verified");
    setText("publicDomainStatus", formatPublicDomainStatus(data.publicDomainStatus));

    renderSource(data.sourceUrl);
    setText("resultOriginalLanguage", originalLanguageName, "Not certain");
    setText("chanceLevel", translateStableValue("chanceLevels", data.chanceLevel, "Not available"));
    const poolSize = Number(data.candidatePoolSize) || 0;
    setText("candidatePoolSize", poolSize === 1 ? "1 passage" : `${poolSize} passages`);

    currentResult = { data, savedRecord: options.savedRecord || null };
    resultReturnView = fromSaved ? "saved" : "home";
    document.querySelector("#resultTitle").textContent = fromSaved ? "Saved passage" : "Result";
    document.querySelector("#returnHomeButton").textContent = fromSaved
      ? "Back to Saved passages"
      : "Back";
    document.querySelector("#drawExplanation").hidden = fromSaved;
    drawAgainButton.hidden = fromSaved;
    updateSaveButton();
    renderStorageError();

    setCardFlipped(false, false);
    showView("result");
    requestAnimationFrame(() => {
      syncCardHeight();
      document.querySelector("#resultTitle").focus({ preventScroll: true });
      views.result.scrollIntoView({
        behavior: reducedMotion.matches ? "auto" : "smooth",
        block: "start"
      });
    });
  }

  function openSavedPassages() {
    cancelActiveDraw();
    pendingSafetyRequest = null;
    setCardFlipped(false, false);
    refreshSavedUi();
    renderSavedPassages();
    showView("saved");
    requestAnimationFrame(() => document.querySelector("#savedTitle")
      .focus({ preventScroll: true }));
  }

  function handleSavedPassagesButton() {
    if (!views.saved.hidden) {
      returnHome();
      return;
    }
    openSavedPassages();
  }

  function handleResultBack() {
    if (resultReturnView === "saved") {
      openSavedPassages();
      return;
    }
    returnHome();
  }

  function handleSavedStorageChange(event) {
    if (event.key !== null && event.key !== SAVED_STORAGE_KEY) return;
    savedPassages = readSavedPassages();
    refreshSavedUi();
    if (!views.saved.hidden) renderSavedPassages();
  }

  function refreshSavedUi() {
    const count = savedPassages.length;
    savedPassagesCount.textContent = String(count);
    savedViewCount.textContent = `${count} ${count === 1 ? "record" : "records"}`;
    savedViewCount.setAttribute("aria-label",
      `${count} saved ${count === 1 ? "passage" : "passages"}`);
    updateMastheadSavedControl();
    updateSaveButton();
    renderStorageError();
  }

  function updateMastheadSavedControl() {
    const onSavedView = !views.saved.hidden;
    savedPassagesLabel.textContent = onSavedView ? "Back to the Archive" : "Saved passages";
    savedPassagesSeparator.hidden = onSavedView;
    savedPassagesCount.hidden = onSavedView;
    savedPassagesButton.setAttribute("aria-controls", onSavedView ? "homeView" : "savedView");
    savedPassagesButton.setAttribute("aria-label", onSavedView
      ? "Back to the Archive"
      : `Open saved passages, ${savedPassages.length} saved`);
  }

  function updateSaveButton() {
    const id = Number(currentResult?.data?.id);
    const isSaved = Number.isSafeInteger(id)
      && savedPassages.some(passage => passage.id === id);
    savePassageButton.textContent = isSaved ? "Saved" : "Save passage";
    savePassageButton.setAttribute("aria-pressed", String(isSaved));
    savePassageButton.setAttribute("aria-label", isSaved
      ? "Remove this passage from saved passages"
      : "Save this passage in this browser");
  }

  function toggleSavedPassage() {
    if (!currentResult) return;
    const id = Number(currentResult.data.id);
    if (!Number.isSafeInteger(id) || id <= 0) return;

    const existingIndex = savedPassages.findIndex(passage => passage.id === id);
    let next;
    if (existingIndex >= 0) {
      next = savedPassages.filter(passage => passage.id !== id);
    } else {
      const saved = createSavedPassage(currentResult.data);
      if (!saved) {
        savedStorageError = t("saved.errors.invalid", {},
          "This passage could not be saved in this browser.");
        renderStorageError();
        return;
      }
      next = [saved, ...savedPassages.filter(passage => passage.id !== saved.id)];
    }

    if (!persistSavedPassages(next)) return;
    refreshSavedUi();
  }

  function createSavedPassage(data) {
    const workTitle = [data.localizedWorkTitle, data.englishWorkTitle,
      data.workTitle, data.originalWorkTitle]
      .find(value => typeof value === "string" && value.trim());
    return normalizeSavedPassage({
      id: data.id,
      displayLanguage: data.displayLanguage,
      displayText: data.displayText,
      passageOriginal: data.passageOriginal,
      originalLanguage: data.originalLanguage,
      author: data.canonicalAuthor || data.author,
      workTitle,
      originalWorkTitle: data.originalWorkTitle || workTitle,
      year: data.year,
      type: data.type,
      sourceUrl: data.sourceUrl,
      publicDomainStatus: data.publicDomainStatus,
      englishContext: data.englishContextNote,
      englishAuthorBio: data.englishAuthorBio,
      themes: data.themes,
      savedAt: Date.now()
    });
  }

  function readSavedPassages() {
    savedStorageError = "";
    let raw;
    try {
      raw = window.localStorage.getItem(SAVED_STORAGE_KEY);
    } catch {
      savedStorageError = t("saved.errors.unavailable", {},
        "Saved passages are unavailable in this browser.");
      return [];
    }
    if (raw === null) return [];

    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch {
      savedStorageError = t("saved.errors.corrupt", {},
        "Some saved passages could not be read.");
      return [];
    }
    if (!Array.isArray(parsed) || parsed.length > 5000) {
      savedStorageError = t("saved.errors.corrupt", {},
        "Some saved passages could not be read.");
      return [];
    }

    let invalid = false;
    const normalized = [];
    parsed.forEach(value => {
      const passage = normalizeSavedPassage(value);
      if (passage) normalized.push(passage);
      else invalid = true;
    });
    normalized.sort((left, right) => right.savedAt - left.savedAt);

    const ids = new Set();
    const deduplicated = normalized.filter(passage => {
      if (ids.has(passage.id)) {
        invalid = true;
        return false;
      }
      ids.add(passage.id);
      return true;
    });
    if (invalid) {
      savedStorageError = t("saved.errors.corrupt", {},
        "Some saved passages could not be read.");
    }
    return deduplicated;
  }

  function persistSavedPassages(passages) {
    try {
      window.localStorage.setItem(SAVED_STORAGE_KEY, JSON.stringify(passages));
    } catch {
      savedStorageError = t("saved.errors.write", {},
        "This passage could not be saved. Browser storage may be full.");
      renderStorageError();
      return false;
    }
    savedPassages = passages;
    savedStorageError = "";
    return true;
  }

  function normalizeSavedPassage(value) {
    if (!value || typeof value !== "object" || Array.isArray(value)) return null;
    const id = Number(value.id);
    const displayLanguage = savedString(value.displayLanguage, 20);
    const displayText = savedString(value.displayText, 20_000);
    const passageOriginal = savedString(value.passageOriginal, 20_000);
    const originalLanguage = savedString(value.originalLanguage, 20);
    const author = savedString(value.author, 500);
    const workTitle = savedString(value.workTitle, 1000);
    const originalWorkTitle = savedString(value.originalWorkTitle, 1000) || workTitle;
    const type = savedString(value.type, 100);
    const sourceUrl = safeSourceUrl(value.sourceUrl);
    const publicDomainStatus = savedString(value.publicDomainStatus, 1000);
    const englishContext = savedString(value.englishContext, 10_000);
    const englishAuthorBio = savedString(value.englishAuthorBio, 10_000);
    const themes = normalizeSavedThemes(value.themes);
    const year = Number(value.year);
    const savedAt = Number(value.savedAt);

    if (!Number.isSafeInteger(id) || id <= 0
        || !displayLanguage || !displayText || !passageOriginal || !originalLanguage
        || !author || !workTitle || !Number.isInteger(year) || !type || !sourceUrl
        || !publicDomainStatus || !englishContext || !englishAuthorBio || !themes
        || !Number.isFinite(savedAt) || savedAt <= 0) return null;

    return {
      id,
      displayLanguage,
      displayText,
      passageOriginal,
      originalLanguage,
      author,
      workTitle,
      originalWorkTitle,
      year,
      type,
      sourceUrl,
      publicDomainStatus,
      englishContext,
      englishAuthorBio,
      themes,
      savedAt
    };
  }

  function savedString(value, maxLength) {
    if (typeof value !== "string" || !value.trim() || value.length > maxLength) return null;
    return value;
  }

  function normalizeSavedThemes(value) {
    if (!Array.isArray(value) || value.length < 1 || value.length > 3) return null;
    const themes = [...new Set(value.filter(theme => typeof theme === "string"
      && /^[a-z][a-z0-9-]{0,39}$/.test(theme)))];
    return themes.length === value.length ? themes : null;
  }

  function safeSourceUrl(value) {
    if (typeof value !== "string" || value.length > 4000) return null;
    try {
      const url = new URL(value);
      return ["http:", "https:"].includes(url.protocol) ? value : null;
    } catch {
      return null;
    }
  }

  function renderStorageError() {
    ["saveStorageError", "savedStorageError"].forEach(id => {
      const element = document.querySelector(`#${id}`);
      element.textContent = savedStorageError;
      element.hidden = !savedStorageError;
    });
  }

  function renderSavedPassages() {
    savedGrid.replaceChildren();
    savedEmptyState.hidden = savedPassages.length > 0;
    const fragment = document.createDocumentFragment();
    savedPassages.forEach(passage => fragment.append(createSavedCard(passage)));
    savedGrid.append(fragment);
  }

  function createSavedCard(passage) {
    const card = document.createElement("article");
    card.className = "saved-card";
    card.setAttribute("role", "listitem");
    card.dataset.savedId = String(passage.id);

    const openButton = document.createElement("button");
    openButton.type = "button";
    openButton.className = "saved-card-open";
    openButton.setAttribute("aria-label",
      `Open saved passage by ${passage.author}, ${passage.workTitle}`);
    openButton.addEventListener("click", () => openSavedPassage(passage.id));

    const recordNumber = document.createElement("p");
    recordNumber.className = "saved-card-number";
    recordNumber.textContent = `No. ${String(passage.id).padStart(4, "0")}`;
    const header = document.createElement("div");
    header.className = "saved-card-header";
    const actions = document.createElement("div");
    actions.className = "saved-card-actions";
    const share = document.createElement("button");
    share.type = "button";
    share.className = "saved-card-share";
    share.textContent = "Share card";
    share.setAttribute("aria-label", `Share passage by ${passage.author}`);
    share.addEventListener("click", event => {
      event.preventDefault();
      event.stopPropagation();
      void prepareShareCard(savedPassageToResult(passage), "saved", share);
    });
    actions.append(share);
    header.append(recordNumber, actions);

    const sides = document.createElement("div");
    sides.className = "saved-card-sides";

    const front = document.createElement("section");
    front.className = "saved-card-front";
    front.setAttribute("aria-label", "Passage side preview");
    const frontType = document.createElement("p");
    frontType.className = "saved-card-type";
    frontType.textContent = translateStableValue("types", passage.type, passage.type);
    const frontLanguage = document.createElement("p");
    frontLanguage.className = "saved-card-language";
    frontLanguage.textContent = languageNameFor(passage.displayLanguage);
    const excerpt = document.createElement("blockquote");
    excerpt.className = "saved-card-passage";
    excerpt.textContent = passage.displayText;
    setLanguageAttributes(excerpt, passage.displayLanguage);

    const credit = document.createElement("div");
    credit.className = "saved-card-credit";
    const author = document.createElement("p");
    author.className = "saved-card-author";
    author.textContent = passage.author;
    author.dir = "auto";
    const work = document.createElement("cite");
    work.className = "saved-card-work";
    work.textContent = passage.workTitle;
    work.dir = "auto";
    const frontYear = document.createElement("span");
    frontYear.className = "saved-card-year";
    frontYear.textContent = String(passage.year);
    const workLine = document.createElement("p");
    workLine.className = "saved-card-work-line";
    workLine.append(work, document.createTextNode(" · "), frontYear);
    credit.append(author, workLine);
    front.append(frontType, frontLanguage, excerpt, credit);

    const back = document.createElement("section");
    back.className = "saved-card-back";
    back.setAttribute("aria-label", "Details side preview");
    const originalHeading = document.createElement("h3");
    originalHeading.className = "saved-card-back-heading";
    originalHeading.textContent = "Original";
    const original = document.createElement("blockquote");
    original.className = "saved-card-original";
    original.textContent = passage.passageOriginal;
    setLanguageAttributes(original, passage.originalLanguage);

    const metadata = document.createElement("dl");
    metadata.className = "saved-card-back-meta";
    appendSavedMetadata(metadata, "Language", languageNameFor(passage.originalLanguage));
    appendSavedMetadata(metadata, "Work", passage.originalWorkTitle);
    appendSavedMetadata(metadata, "Author", passage.author);
    appendSavedMetadata(metadata, "Year", String(passage.year));
    appendSavedMetadata(metadata, "Type", translateStableValue("types", passage.type, passage.type));

    const backDetails = document.createElement("div");
    backDetails.className = "saved-card-back-details";
    backDetails.append(
      createSavedBackSection("About this passage", passage.englishContext, "saved-card-context"),
      createSavedBackSection("About the author", passage.englishAuthorBio, "saved-card-bio"),
      createSavedBackSection("Source", passage.sourceUrl, "saved-card-source"),
      createSavedBackSection("Copyright status", passage.publicDomainStatus, "saved-card-status")
    );
    back.append(originalHeading, original, metadata, backDetails);
    sides.append(front, back);

    const footer = document.createElement("div");
    footer.className = "saved-card-footer";
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "saved-card-remove";
    remove.textContent = "Remove";
    remove.setAttribute("aria-label", `Remove saved passage by ${passage.author}`);
    remove.addEventListener("click", event => {
      event.preventDefault();
      event.stopPropagation();
      removeSavedPassage(passage.id);
    });
    footer.append(remove);
    card.append(openButton, header, sides, footer);
    return card;
  }

  function appendSavedMetadata(list, label, value) {
    const row = document.createElement("div");
    const term = document.createElement("dt");
    term.textContent = label;
    const description = document.createElement("dd");
    description.textContent = value;
    description.dir = "auto";
    row.append(term, description);
    list.append(row);
  }

  function createSavedBackSection(heading, value, className) {
    const section = document.createElement("section");
    section.className = `saved-card-back-section ${className}`;
    const title = document.createElement("h4");
    title.textContent = heading;
    const content = document.createElement("p");
    content.textContent = value;
    section.append(title, content);
    return section;
  }

  function removeSavedPassage(id) {
    const index = savedPassages.findIndex(passage => passage.id === id);
    if (index < 0) return;
    const next = savedPassages.filter(passage => passage.id !== id);
    if (!persistSavedPassages(next)) return;
    refreshSavedUi();
    renderSavedPassages();
    requestAnimationFrame(() => {
      const neighbor = next[Math.min(index, next.length - 1)];
      if (neighbor) {
        savedGrid.querySelector(`[data-saved-id="${neighbor.id}"] .saved-card-open`)?.focus();
      } else {
        savedBackButton.focus();
      }
    });
  }

  function openSavedPassage(id) {
    const passage = savedPassages.find(value => value.id === id);
    if (!passage) return;
    renderResult(savedPassageToResult(passage), { fromSaved: true, savedRecord: passage });
  }

  function savedPassageToResult(passage) {
    return {
      id: passage.id,
      displayLanguage: passage.displayLanguage,
      displayLanguageEnglishName: languageNameFor(passage.displayLanguage),
      displayText: passage.displayText,
      passageOriginal: passage.passageOriginal,
      originalLanguage: passage.originalLanguage,
      originalLanguageEnglishName: languageNameFor(passage.originalLanguage),
      canonicalAuthor: passage.author,
      author: passage.author,
      localizedWorkTitle: passage.workTitle,
      englishWorkTitle: passage.workTitle,
      originalWorkTitle: passage.originalWorkTitle,
      workTitle: passage.workTitle,
      year: passage.year,
      type: passage.type,
      sourceUrl: passage.sourceUrl,
      publicDomainStatus: passage.publicDomainStatus,
      englishContextNote: passage.englishContext,
      englishAuthorBio: passage.englishAuthorBio,
      englishTranslationNote: null,
      themes: passage.themes,
      chanceLevel: null,
      candidatePoolSize: null
    };
  }

  async function prepareShareCard(data, returnView, trigger) {
    if (sharePreparing) return;
    const payload = createSharePayload(data);
    resetSharePreview();
    const generation = shareGeneration;
    shareState = {
      payload,
      returnView: returnView === "saved" ? "saved" : "result",
      returnFocus: trigger instanceof HTMLElement ? trigger : null,
      blob: null,
      file: null,
      previewUrl: null
    };
    showView("share");
    setSharePreparing(true);
    requestAnimationFrame(() => document.querySelector("#shareTitle")
      .focus({ preventScroll: true }));

    if (!payload) {
      setSharePreparing(false);
      showShareError("This passage could not be prepared as a share card.");
      return;
    }

    try {
      await waitForShareFonts(payload);
      if (!shareState || generation !== shareGeneration) return;
      const canvas = renderShareCanvas(payload);
      const blob = await canvasToPngBlob(canvas);
      if (!shareState || generation !== shareGeneration) return;
      const filename = shareCardFilename(payload.id);
      const file = typeof File === "function"
        ? new File([blob], filename, { type: "image/png", lastModified: Date.now() })
        : null;
      const previewUrl = URL.createObjectURL(blob);
      shareState.blob = blob;
      shareState.file = file;
      shareState.previewUrl = previewUrl;
      sharePreviewImage.alt = `Literary Oracle share card for ${payload.author}, ${payload.localizedWorkTitle}`;
      sharePreviewImage.src = previewUrl;
      sharePreviewImage.hidden = false;
      await waitForSharePreviewImage();
      if (!shareState || generation !== shareGeneration) return;
      nativeShareButton.hidden = !canSharePreparedFile(file);
      downloadShareButton.disabled = false;
    } catch {
      if (shareState && generation === shareGeneration) {
        if (shareState.previewUrl) URL.revokeObjectURL(shareState.previewUrl);
        shareState.previewUrl = null;
        shareState.blob = null;
        shareState.file = null;
        sharePreviewImage.removeAttribute("src");
        sharePreviewImage.hidden = true;
        nativeShareButton.hidden = true;
        downloadShareButton.disabled = true;
        showShareError("The image could not be generated in this browser.");
      }
    } finally {
      if (shareState && generation === shareGeneration) setSharePreparing(false);
    }
  }

  function createSharePayload(data) {
    if (!data || typeof data !== "object") return null;
    const id = Number(data.id);
    const displayLanguage = shareString(data.displayLanguage, 20);
    const displayText = shareString(data.displayText, 20_000);
    const originalLanguage = shareString(data.originalLanguage, 20);
    const passageOriginal = shareString(data.passageOriginal, 20_000);
    const author = shareString(data.canonicalAuthor || data.author, 500);
    const localizedWorkTitle = [data.localizedWorkTitle, data.workTitle,
      data.englishWorkTitle, data.originalWorkTitle]
      .map(value => shareString(value, 1000)).find(Boolean);
    const originalWorkTitle = shareString(data.originalWorkTitle, 1000)
      || localizedWorkTitle;
    const year = Number(data.year);
    const source = normalizeShareSource(data.sourceUrl);
    if (!Number.isSafeInteger(id) || id <= 0 || !displayLanguage || !displayText
        || !originalLanguage || !passageOriginal || !author || !localizedWorkTitle
        || !originalWorkTitle || !Number.isInteger(year) || !source) return null;
    return Object.freeze({
      id,
      displayLanguage,
      displayText,
      originalLanguage,
      passageOriginal,
      author,
      localizedWorkTitle,
      originalWorkTitle,
      year,
      sourceUrl: source.url,
      sourceLabel: source.label
    });
  }

  function shareString(value, maxLength) {
    if (typeof value !== "string" || !value.trim() || value.length > maxLength) return null;
    return value;
  }

  function normalizeShareSource(value) {
    if (typeof value !== "string" || value.length > 4000) return null;
    try {
      const url = new URL(value);
      if (!["http:", "https:"].includes(url.protocol) || !url.hostname
          || url.username || url.password) return null;
      return Object.freeze({ url: url.href, label: shareSourceLabel(url.hostname) });
    } catch {
      return null;
    }
  }

  function shareSourceLabel(rawHostname) {
    const hostname = String(rawHostname).toLowerCase().replace(/\.$/, "");
    const isDomain = domain => hostname === domain || hostname.endsWith(`.${domain}`);
    if (isDomain("wikisource.org")) return "Wikisource";
    if (isDomain("gutenberg.net.au")) return "Project Gutenberg Australia";
    if (isDomain("gutenberg.org")) return "Project Gutenberg";
    if (isDomain("projekt-gutenberg.org")) return "Projekt Gutenberg-DE";
    if (isDomain("aozora.gr.jp")) return "Aozora Bunko";
    return hostname.replace(/^www\./, "");
  }

  function shareCardFilename(id) {
    return `literary-oracle-no-${String(id).padStart(4, "0")}.png`;
  }

  async function waitForShareFonts(payload) {
    if (!document.fonts?.ready) return;
    try {
      if (typeof document.fonts.load === "function") {
        await Promise.allSettled([
          document.fonts.load(`500 52px ${shareFontFamily(payload.displayLanguage)}`,
            payload.displayText.slice(0, 96)),
          document.fonts.load(`400 36px ${shareFontFamily(payload.originalLanguage)}`,
            payload.passageOriginal.slice(0, 96))
        ]);
      }
      await document.fonts.ready;
    } catch {
      // Canvas system-serif fallbacks remain available when a webfont fails.
    }
  }

  function waitForSharePreviewImage() {
    if (typeof sharePreviewImage.decode === "function") {
      return sharePreviewImage.decode().catch(() => {
        if (!sharePreviewImage.complete || !sharePreviewImage.naturalWidth) {
          throw new Error("share-preview-unavailable");
        }
      });
    }
    if (sharePreviewImage.complete && sharePreviewImage.naturalWidth) return Promise.resolve();
    return new Promise((resolve, reject) => {
      sharePreviewImage.addEventListener("load", resolve, { once: true });
      sharePreviewImage.addEventListener("error",
        () => reject(new Error("share-preview-unavailable")), { once: true });
    });
  }

  function renderShareCanvas(payload) {
    const measuringCanvas = document.createElement("canvas");
    measuringCanvas.width = SHARE_CARD_WIDTH;
    measuringCanvas.height = SHARE_CARD_MAX_HEIGHT;
    const measuringContext = measuringCanvas.getContext("2d");
    if (!measuringContext) throw new Error("share-canvas-unavailable");

    let layout = null;
    for (let displayFontSize = 52; displayFontSize >= 34; displayFontSize -= 2) {
      const candidate = measureShareLayout(measuringContext, payload, displayFontSize);
      if (candidate.widthFits && candidate.requiredHeight <= SHARE_CARD_MIN_HEIGHT) {
        layout = candidate;
        break;
      }
    }
    if (!layout) {
      const minimumReadableLayout = measureShareLayout(measuringContext, payload, 34);
      if (minimumReadableLayout.widthFits
          && minimumReadableLayout.requiredHeight <= SHARE_CARD_MAX_HEIGHT) {
        layout = minimumReadableLayout;
      }
    }
    if (!layout) throw new Error("share-card-too-long");

    const canvas = document.createElement("canvas");
    canvas.width = SHARE_CARD_WIDTH;
    canvas.height = Math.max(SHARE_CARD_MIN_HEIGHT,
      Math.ceil(layout.requiredHeight / 10) * 10);
    const context = canvas.getContext("2d");
    if (!context) throw new Error("share-canvas-unavailable");
    drawShareCard(context, canvas, payload, layout);
    return canvas;
  }

  function measureShareLayout(context, payload, displayFontSize) {
    const contentWidth = 808;
    const includeOriginal = payload.displayLanguage !== payload.originalLanguage;
    const displayLineHeight = Math.round(displayFontSize * 1.36);
    const originalFontSize = Math.max(27, Math.round(displayFontSize * .68));
    const originalLineHeight = Math.round(originalFontSize * 1.43);
    const titleFontSize = Math.max(24, Math.round(displayFontSize * .56));
    const titleLineHeight = Math.round(titleFontSize * 1.35);
    setShareCanvasFont(context, displayFontSize, payload.displayLanguage, "500");
    const displayLines = wrapCanvasText(context, payload.displayText,
      contentWidth, payload.displayLanguage);
    setShareCanvasFont(context, originalFontSize, payload.originalLanguage, "400");
    const originalLines = includeOriginal
      ? wrapCanvasText(context, payload.passageOriginal, contentWidth, payload.originalLanguage)
      : [];
    setShareCanvasFont(context, 32, "en", "500");
    const authorLines = wrapCanvasText(context, `— ${payload.author}`, contentWidth, "en");
    setShareCanvasFont(context, titleFontSize, payload.displayLanguage, "400", "italic");
    const titlesDiffer = payload.localizedWorkTitle.trim() !== payload.originalWorkTitle.trim();
    const localizedTitleLines = titlesDiffer
      ? wrapCanvasText(context, payload.localizedWorkTitle, contentWidth, payload.displayLanguage)
      : [];
    setShareCanvasFont(context, titleFontSize, payload.originalLanguage, "400", "italic");
    const originalTitleText = titlesDiffer
      ? `${payload.originalWorkTitle} · ${payload.year}`
      : `${payload.localizedWorkTitle} · ${payload.year}`;
    const originalTitleLines = wrapCanvasText(context, originalTitleText,
      contentWidth, payload.originalLanguage);
    setShareCanvasFont(context, 24, "en", "400");
    const originalLanguageLines = wrapCanvasText(context,
      `Original language: ${languageNameFor(payload.originalLanguage)}`, contentWidth, "en");
    const sourceLabelLines = wrapCanvasText(context,
      `Source: ${payload.sourceLabel}`, contentWidth, "en");

    const widthChecks = [
      [displayLines, displayFontSize, payload.displayLanguage, "500", "normal"],
      [originalLines, originalFontSize, payload.originalLanguage, "400", "normal"],
      [authorLines, 32, "en", "500", "normal"],
      [localizedTitleLines, titleFontSize, payload.displayLanguage, "400", "italic"],
      [originalTitleLines, titleFontSize, payload.originalLanguage, "400", "italic"],
      [originalLanguageLines, 24, "en", "400", "normal"],
      [sourceLabelLines, 24, "en", "400", "normal"]
    ];
    const widthFits = widthChecks.every(([lines, size, language, weight, style]) => {
      setShareCanvasFont(context, size, language, weight, style);
      return lines.every(line => !line || context.measureText(line).width <= contentWidth + .5);
    });

    let requiredHeight = 248 + displayLines.length * displayLineHeight;
    if (includeOriginal) {
      requiredHeight += 104 + originalLines.length * originalLineHeight;
    }
    requiredHeight += 62 + authorLines.length * 42;
    requiredHeight += (localizedTitleLines.length + originalTitleLines.length) * titleLineHeight;
    requiredHeight += 35;
    if (includeOriginal) requiredHeight += 34;
    requiredHeight += (originalLanguageLines.length + sourceLabelLines.length) * 34;
    requiredHeight += 72;

    return {
      widthFits,
      requiredHeight,
      includeOriginal,
      displayFontSize,
      displayLineHeight,
      displayLines,
      originalFontSize,
      originalLineHeight,
      originalLines,
      titleFontSize,
      titleLineHeight,
      authorLines,
      localizedTitleLines,
      originalTitleLines,
      originalLanguageLines,
      sourceLabelLines,
      titlesDiffer
    };
  }

  function wrapCanvasText(context, text, maxWidth, language) {
    const paragraphs = String(text).replaceAll("\r\n", "\n").replaceAll("\r", "\n").split("\n");
    const lines = [];
    paragraphs.forEach(paragraph => {
      if (!paragraph) {
        lines.push("");
        return;
      }
      const segments = canvasWrapSegments(paragraph, language);
      let current = "";
      segments.forEach(segment => {
        const candidate = current + segment;
        if (!current || context.measureText(candidate).width <= maxWidth) {
          current = candidate;
          return;
        }
        lines.push(current.trimEnd());
        current = segment.trimStart();
      });
      if (current || !segments.length) lines.push(current.trimEnd());
    });
    return lines;
  }

  function canvasWrapSegments(text, language) {
    if (isCjkShareLanguage(language)) return shareGraphemes(text, language);
    if (typeof Intl.Segmenter === "function") {
      try {
        return [...new Intl.Segmenter(language, { granularity: "word" }).segment(text)]
          .map(part => part.segment);
      } catch {
        // The whitespace fallback keeps complete words intact.
      }
    }
    return text.match(/\S+\s*|\s+/gu) || [text];
  }

  function shareGraphemes(text, language) {
    if (typeof Intl.Segmenter === "function") {
      try {
        return [...new Intl.Segmenter(language, { granularity: "grapheme" }).segment(text)]
          .map(part => part.segment);
      } catch {
        // Continue with the Unicode-aware fallback below.
      }
    }
    const groups = [];
    let joinNext = false;
    Array.from(String(text).normalize("NFC")).forEach(character => {
      const attaches = /[\p{Mark}\uFE00-\uFE0F]/u.test(character)
        || character === "\u200D" || joinNext;
      if (attaches && groups.length) groups[groups.length - 1] += character;
      else groups.push(character);
      joinNext = character === "\u200D";
    });
    return groups;
  }

  function isCjkShareLanguage(language) {
    return ["zh-Hans", "zh-Hant", "lzh", "ja", "ko"].includes(language);
  }

  function shareFontFamily(language) {
    const families = {
      "zh-Hans": '"Noto Serif SC", "Songti SC", SimSun, serif',
      "zh-Hant": '"Noto Serif TC", "Songti TC", PMingLiU, serif',
      lzh: '"Noto Serif TC", "Songti TC", PMingLiU, serif',
      ja: '"Noto Serif JP", "Yu Mincho", "MS PMincho", serif',
      ko: '"Noto Serif KR", Batang, serif',
      ar: '"Noto Naskh Arabic", "Traditional Arabic", serif',
      fa: '"Noto Naskh Arabic", "Traditional Arabic", serif',
      hi: '"Noto Serif Devanagari", "Nirmala UI", serif',
      bn: '"Noto Serif Bengali", "Nirmala UI", serif',
      th: '"Noto Serif Thai", Tahoma, serif'
    };
    return families[language]
      || '"EB Garamond", "Libre Caslon Text", Georgia, "Times New Roman", serif';
  }

  function setShareCanvasFont(context, size, language, weight = "400", style = "normal") {
    context.font = `${style} ${weight} ${size}px ${shareFontFamily(language)}`;
  }

  function isRtlShareLanguage(language) {
    return directionFor(language) === "rtl" || ["ar", "fa"].includes(language);
  }

  function drawShareCard(context, canvas, payload, layout) {
    drawShareBackground(context, canvas.width, canvas.height);
    const cardX = 72;
    const cardY = 64;
    const cardWidth = canvas.width - 144;
    const cardHeight = canvas.height - 128;
    context.save();
    context.shadowColor = "rgba(25, 28, 26, .26)";
    context.shadowBlur = 20;
    context.shadowOffsetX = 10;
    context.shadowOffsetY = 14;
    roundedShareRect(context, cardX, cardY, cardWidth, cardHeight, 3);
    context.fillStyle = "#E5E8E5";
    context.fill();
    context.restore();
    roundedShareRect(context, cardX, cardY, cardWidth, cardHeight, 3);
    context.strokeStyle = "#292B29";
    context.lineWidth = 3;
    context.stroke();
    roundedShareRect(context, cardX + 10, cardY + 10, cardWidth - 20, cardHeight - 20, 2);
    context.strokeStyle = "rgba(111, 125, 130, .78)";
    context.lineWidth = 1;
    context.stroke();

    const left = cardX + 64;
    const right = cardX + cardWidth - 64;
    const contentWidth = right - left;
    setShareCanvasFont(context, 31, "en", "500");
    context.fillStyle = "#292B29";
    context.textBaseline = "alphabetic";
    context.direction = "ltr";
    context.textAlign = "left";
    context.fillText("Literary Oracle", left, cardY + 78);
    setShareCanvasFont(context, 24, "en", "400");
    context.fillStyle = "#6F7D82";
    context.textAlign = "right";
    context.fillText(`No. ${String(payload.id).padStart(4, "0")}`, right, cardY + 76);

    let y = cardY + 172;
    y = drawShareLines(context, layout.displayLines, left, right, y,
      layout.displayLineHeight, layout.displayFontSize, payload.displayLanguage, "500");

    if (layout.includeOriginal) {
      y += 36;
      context.beginPath();
      context.moveTo(left, y);
      context.lineTo(right, y);
      context.strokeStyle = "rgba(111, 125, 130, .72)";
      context.lineWidth = 1;
      context.stroke();
      y += 52;
      y = drawShareLines(context, layout.originalLines, left, right, y,
        layout.originalLineHeight, layout.originalFontSize, payload.originalLanguage, "400");
    }

    y += 48;
    y = drawShareLines(context, layout.authorLines, left, right, y,
      42, 32, "en", "500");
    y = drawShareLines(context, layout.localizedTitleLines, left, right, y,
      layout.titleLineHeight, layout.titleFontSize, payload.displayLanguage, "400", "italic");
    y = drawShareLines(context, layout.originalTitleLines, left, right, y,
      layout.titleLineHeight, layout.titleFontSize, payload.originalLanguage, "400", "italic");

    y += 35;
    setShareCanvasFont(context, 24, "en", "400");
    context.direction = "ltr";
    context.textAlign = "left";
    context.fillStyle = "#7E4C55";
    if (layout.includeOriginal) {
      context.fillText("Project translation", left, y);
      y += 34;
    }
    y = drawShareLines(context, layout.originalLanguageLines, left, right, y,
      34, 24, "en", "400", "normal", "#6F756F");
    drawShareLines(context, layout.sourceLabelLines, left, right, y,
      34, 24, "en", "400", "normal", "#6F756F");
    context.direction = "ltr";
    context.textAlign = "left";
  }

  function drawShareBackground(context, width, height) {
    context.fillStyle = "#56635A";
    context.fillRect(0, 0, width, height);
    const tile = document.createElement("canvas");
    tile.width = 52;
    tile.height = 52;
    const tileContext = tile.getContext("2d");
    if (!tileContext) return;
    tileContext.fillStyle = "rgba(196, 202, 197, .075)";
    tileContext.save();
    tileContext.translate(26, 26);
    for (let petal = 0; petal < 4; petal += 1) {
      tileContext.beginPath();
      tileContext.moveTo(0, 0);
      tileContext.bezierCurveTo(-3.5, -3.5, -3.5, -9, 0, -13);
      tileContext.bezierCurveTo(3.5, -9, 3.5, -3.5, 0, 0);
      tileContext.closePath();
      tileContext.fill();
      tileContext.rotate(Math.PI / 2);
    }
    tileContext.restore();
    const pattern = context.createPattern(tile, "repeat");
    if (pattern) {
      context.fillStyle = pattern;
      context.fillRect(0, 0, width, height);
    }
  }

  function roundedShareRect(context, x, y, width, height, radius) {
    const r = Math.min(radius, width / 2, height / 2);
    context.beginPath();
    context.moveTo(x + r, y);
    context.lineTo(x + width - r, y);
    context.quadraticCurveTo(x + width, y, x + width, y + r);
    context.lineTo(x + width, y + height - r);
    context.quadraticCurveTo(x + width, y + height, x + width - r, y + height);
    context.lineTo(x + r, y + height);
    context.quadraticCurveTo(x, y + height, x, y + height - r);
    context.lineTo(x, y + r);
    context.quadraticCurveTo(x, y, x + r, y);
    context.closePath();
  }

  function drawShareLines(context, lines, left, right, startY, lineHeight,
      fontSize, language, weight, style = "normal", color = "#292B29") {
    setShareCanvasFont(context, fontSize, language, weight, style);
    const rtl = isRtlShareLanguage(language);
    context.direction = rtl ? "rtl" : "ltr";
    context.textAlign = rtl ? "right" : "left";
    context.fillStyle = color;
    let y = startY;
    lines.forEach(line => {
      if (line) context.fillText(line, rtl ? right : left, y);
      y += lineHeight;
    });
    return y;
  }

  function canvasToPngBlob(canvas) {
    return new Promise((resolve, reject) => {
      canvas.toBlob(blob => {
        if (blob && blob.type === "image/png") resolve(blob);
        else reject(new Error("share-png-unavailable"));
      }, "image/png");
    });
  }

  function canSharePreparedFile(file) {
    if (!file || typeof navigator.share !== "function"
        || typeof navigator.canShare !== "function") return false;
    try {
      return navigator.canShare({ files: [file] });
    } catch {
      return false;
    }
  }

  async function sharePreparedCard() {
    if (!shareState?.file || nativeShareButton.hidden || nativeShareButton.disabled) return;
    nativeShareButton.disabled = true;
    clearShareError();
    const payload = shareState.payload;
    try {
      await navigator.share({
        files: [shareState.file],
        title: `${payload.author} — ${payload.localizedWorkTitle}`,
        text: `${payload.author} — ${payload.localizedWorkTitle} (${payload.year})\n${payload.sourceUrl}\nShared from Literary Oracle`
      });
    } catch (error) {
      if (error?.name !== "AbortError") {
        showShareError("The system share menu could not be opened. You can still download the image.");
      }
    } finally {
      nativeShareButton.disabled = false;
    }
  }

  function downloadPreparedCard() {
    if (!shareState?.blob || downloadShareButton.disabled) return;
    clearShareError();
    let downloadUrl;
    try {
      downloadUrl = URL.createObjectURL(shareState.blob);
      const link = document.createElement("a");
      link.href = downloadUrl;
      link.download = shareCardFilename(shareState.payload.id);
      link.rel = "noopener";
      document.body.append(link);
      link.click();
      link.remove();
      window.setTimeout(() => URL.revokeObjectURL(downloadUrl), 1000);
    } catch {
      if (downloadUrl) URL.revokeObjectURL(downloadUrl);
      showShareError("The image could not be downloaded in this browser.");
    }
  }

  function setSharePreparing(preparing) {
    sharePreparing = preparing;
    views.share.toggleAttribute("aria-busy", preparing);
    sharePreparingStatus.hidden = !preparing;
    if (preparing) sharePreparingStatus.textContent = "Preparing card…";
    downloadShareButton.disabled = preparing || !shareState?.blob;
    if (preparing) nativeShareButton.hidden = true;
  }

  function showShareError(message) {
    shareError.textContent = message;
    shareError.hidden = false;
    shareError.focus({ preventScroll: true });
  }

  function clearShareError() {
    shareError.textContent = "";
    shareError.hidden = true;
  }

  function returnFromSharePreview() {
    const returnView = shareState?.returnView === "saved" ? "saved" : "result";
    const returnFocus = shareState?.returnFocus;
    showView(returnView);
    requestAnimationFrame(() => {
      if (returnView === "result") syncCardHeight();
      if (returnFocus?.isConnected) returnFocus.focus({ preventScroll: true });
      else if (returnView === "saved") document.querySelector("#savedTitle").focus({ preventScroll: true });
      else shareCardButton.focus({ preventScroll: true });
    });
  }

  function handleDocumentKeydown(event) {
    if (event.key !== "Escape" || views.share.hidden) return;
    event.preventDefault();
    returnFromSharePreview();
  }

  function resetSharePreview() {
    shareGeneration += 1;
    sharePreparing = false;
    if (shareState?.previewUrl) URL.revokeObjectURL(shareState.previewUrl);
    shareState = null;
    sharePreviewImage.removeAttribute("src");
    sharePreviewImage.alt = "";
    sharePreviewImage.hidden = true;
    sharePreparingStatus.hidden = false;
    views.share.removeAttribute("aria-busy");
    nativeShareButton.hidden = true;
    nativeShareButton.disabled = false;
    downloadShareButton.disabled = true;
    clearShareError();
  }

  function renderCrisis(data) {
    const fallback = t(
      "crisis.message",
      {},
      "If you may hurt yourself or are in immediate danger, contact your local emergency services now. Contact someone you trust and ask them to stay with you while you reach support. You can also find confidential support in your country through Find a Helpline."
    );
    setText("crisisMessage", data?.message, fallback);
    showView("crisis");
    requestAnimationFrame(() => document.querySelector("#crisisTitle").focus({ preventScroll: true }));
  }

  function renderSafetyConfirmation() {
    setText("safetyConfirmationTitle", t(
      "safetyConfirmation.title",
      {},
      "Are you safe right now?"
    ));
    setText("safetyConfirmationMessage", t(
      "safetyConfirmation.message",
      {},
      "If you may act on thoughts of hurting yourself, immediate human support matters more than a literary passage."
    ));
    setText("safetyNeedSupportButton", t(
      "safetyConfirmation.needSupport",
      {},
      "I need immediate support"
    ));
    setText("safetyContinueButton", t(
      "safetyConfirmation.continue",
      {},
      "I’m safe right now — continue"
    ));
    showView("safetyConfirmation");
    requestAnimationFrame(() => document.querySelector("#safetyConfirmationTitle")
      .focus({ preventScroll: true }));
  }

  function handleImmediateSupport() {
    pendingSafetyRequest = null;
    renderCrisis();
  }

  function handleSafetyContinue() {
    if (!pendingSafetyRequest || isDrawing) return;
    const acknowledgedRequest = { ...pendingSafetyRequest, safetyAcknowledged: true };
    void beginDraw(acknowledgedRequest);
  }

  function renderSource(sourceUrl) {
    const container = document.querySelector("#source");
    container.replaceChildren();
    container.dir = "ltr";
    if (!sourceUrl) {
      container.textContent = "Not yet verified";
      return;
    }

    try {
      const url = new URL(sourceUrl);
      if (!["http:", "https:"].includes(url.protocol)) {
        throw new Error("unsupported source protocol");
      }
      const link = document.createElement("a");
      link.href = url.href;
      link.target = "_blank";
      link.rel = "noopener noreferrer";
      link.textContent = url.href;
      link.lang = "en";
      link.dir = "ltr";
      link.setAttribute("aria-label", `Open source ${url.href} in a new tab`);
      container.append(link);
    } catch {
      container.textContent = "Not yet verified";
    }
  }

  function handleCardClick(event) {
    const interactiveTarget = event.target.closest(
      "a, button, input, select, textarea, summary, [contenteditable='true']"
    );
    const selectedByThisGesture = cardPointerMoved && hasActiveTextSelection();
    resetCardPointerGesture();
    if (interactiveTarget || selectedByThisGesture) return;
    toggleCard();
  }

  function handleCardPointerDown(event) {
    if (event.button !== 0) return;
    cardPointerOrigin = { x: event.clientX, y: event.clientY };
    cardPointerMoved = false;
  }

  function handleCardPointerMove(event) {
    if (!cardPointerOrigin || cardPointerMoved) return;
    const horizontalDistance = event.clientX - cardPointerOrigin.x;
    const verticalDistance = event.clientY - cardPointerOrigin.y;
    cardPointerMoved = Math.hypot(horizontalDistance, verticalDistance) > CARD_DRAG_THRESHOLD_PX;
  }

  function resetCardPointerGesture() {
    cardPointerOrigin = null;
    cardPointerMoved = false;
  }

  function hasActiveTextSelection() {
    const selection = window.getSelection();
    return Boolean(selection && !selection.isCollapsed);
  }

  function handleCardKeydown(event) {
    if (event.target !== archiveCard || (event.key !== "Enter" && event.key !== " ")) return;
    event.preventDefault();
    toggleCard();
  }

  function toggleCard() {
    setCardFlipped(!cardInner.classList.contains("is-flipped"), true);
  }

  function setCardFlipped(flipped, announce = true) {
    const cardHadFocus = document.activeElement === archiveCard;
    cardInner.classList.toggle("is-flipped", flipped);
    archiveCard.setAttribute("role", flipped ? "group" : "button");
    archiveCard.tabIndex = flipped ? -1 : 0;
    if (flipped) archiveCard.removeAttribute("aria-expanded");
    else archiveCard.setAttribute("aria-expanded", "false");
    flipButton.setAttribute("aria-expanded", String(flipped));
    cardFront.setAttribute("aria-hidden", String(flipped));
    cardBack.setAttribute("aria-hidden", String(!flipped));
    cardFront.toggleAttribute("inert", flipped);
    cardBack.toggleAttribute("inert", !flipped);
    updateCardAccessibility(flipped, announce);
    if (flipped && cardHadFocus) flipButton.focus({ preventScroll: true });
    requestAnimationFrame(syncCardHeight);
  }

  function updateCardAccessibility(flipped, announce) {
    const actionLabel = flipped ? "Passage" : "Details";
    const accessibleLabel = flipped
      ? "Details side shown; return to the passage"
      : "Passage side shown; open archive details";
    flipButton.textContent = actionLabel;
    flipButton.setAttribute("aria-label", accessibleLabel);
    archiveCard.setAttribute("aria-label", flipped ? "Details side" : accessibleLabel);
    if (announce) cardStatus.textContent = flipped ? "Details side shown." : "Passage side shown.";
  }

  function syncCardHeight() {
    if (views.result.hidden) return;
    const activeFace = cardInner.classList.contains("is-flipped") ? cardBack : cardFront;
    cardInner.style.height = `${Math.ceil(activeFace.scrollHeight)}px`;
  }

  function updateChanceReading() {
    const value = Number(chanceInput.value);
    chanceValue.value = String(value);
    chanceDescription.textContent = value < 34
      ? "Narrow candidate pool"
      : value < 67 ? "Medium candidate pool" : "Wide candidate pool";
  }

  function setLoading(loading) {
    drawButton.disabled = loading;
    drawAgainButton.disabled = loading;
    drawButton.querySelector("span").textContent = loading ? "Loading…" : "Draw from the archive";
  }

  function cancelActiveDraw() {
    if (activeRequest) activeRequest.abort();
    activeRequest = null;
    isDrawing = false;
    window.clearTimeout(waitingTimer);
    waitingTimer = null;
    setLoading(false);
    mainContent.removeAttribute("aria-busy");
  }

  function returnHome() {
    cancelActiveDraw();
    pendingSafetyRequest = null;
    resultReturnView = "home";
    setCardFlipped(false, false);
    showView("home");
    requestAnimationFrame(() => promptInput.focus({ preventScroll: true }));
  }

  function showView(name) {
    if (name !== "share" && !views.share.hidden) resetSharePreview();
    Object.entries(views).forEach(([viewName, element]) => {
      element.hidden = viewName !== name;
    });
    savedPassagesButton.removeAttribute("aria-current");
    updateMastheadSavedControl();
  }

  function showFormError(message) {
    formError.textContent = message;
    formError.hidden = false;
  }

  function clearFormError() {
    formError.hidden = true;
    formError.textContent = "";
    promptInput.removeAttribute("aria-invalid");
  }

  function readRecentIds() {
    try {
      const stored = JSON.parse(localStorage.getItem(HISTORY_KEY) || "[]");
      return Array.isArray(stored) ? stored.filter(Number.isFinite).slice(0, 10) : [];
    } catch {
      return [];
    }
  }

  function rememberResult(id) {
    if (!Number.isFinite(id)) return;
    const ids = [id, ...readRecentIds().filter(existing => existing !== id)].slice(0, 10);
    try {
      localStorage.setItem(HISTORY_KEY, JSON.stringify(ids));
    } catch {
      // Storage restrictions must not prevent a draw from completing.
    }
  }

  function formatPublicDomainStatus(status) {
    if (!status) return "Not yet verified";
    const values = String(status).split(";").map(value => value.trim()).filter(Boolean);
    const labels = values.map(value => t(`statuses.${value}`, {}, "")).filter(Boolean);
    return labels.length === values.length ? labels.join("; ") : "Not yet verified";
  }

  function formatTranslationNote(note) {
    if (!note) return "";
    const value = String(note);
    if (/machine-assisted/i.test(value)) return "Machine-assisted project translation.";
    if (/no translation/i.test(value)) return "Original text; no translation.";
    return value;
  }

  function translateStableValue(section, value, fallback) {
    if (!value) return fallback;
    return t(`${section}.${String(value)}`, {}, fallback);
  }

  function displayValue(value) {
    return value === null || value === undefined || value === "" ? "Not yet verified" : String(value);
  }

  function setText(id, value, fallback = "") {
    document.querySelector(`#${id}`).textContent = value === null || value === undefined || value === ""
      ? fallback
      : String(value);
  }

  function setLanguageAttributes(element, code) {
    if (code) element.lang = code;
    else element.removeAttribute("lang");
    element.dir = "auto";
  }

  function languageNameFor(code) {
    return languageByCode.get(code)?.englishName || ENGLISH_LANGUAGE_NAMES[code] || "Not certain";
  }

  function directionFor(code) {
    return languageByCode.get(code)?.direction || (code === "ar" ? "rtl" : "ltr");
  }

  function normalizeSupportedCode(rawCode) {
    if (!rawCode) return "";
    const raw = String(rawCode).replaceAll("_", "-");
    const exact = supportedLanguages.find(language => language.code.toLowerCase() === raw.toLowerCase());
    if (exact) return exact.code;
    if (raw.toLowerCase().startsWith("zh")) {
      const traditional = /(?:hant|tw|hk|mo)/i.test(raw);
      const chineseCode = traditional ? "zh-Hant" : "zh-Hans";
      return languageByCode.has(chineseCode) ? chineseCode : "";
    }
    const base = raw.split("-")[0].toLowerCase();
    return supportedLanguages.find(language => language.code.toLowerCase() === base)?.code || "";
  }

  function resolveBrowserLanguage(rawCode) {
    return normalizeSupportedCode(rawCode) || FALLBACK_LANGUAGE;
  }

  function t(path, replacements = {}, fallback = "") {
    const value = getNested(englishTranslations, path);
    const text = typeof value === "string" ? value : fallback;
    return Object.entries(replacements).reduce(
      (current, [key, replacement]) => current.replaceAll(`{${key}}`, String(replacement)),
      text
    );
  }

  function getNested(object, path) {
    return path.split(".").reduce((current, key) => current?.[key], object);
  }

  function delay(milliseconds) {
    return new Promise(resolve => window.setTimeout(resolve, milliseconds));
  }
})();
