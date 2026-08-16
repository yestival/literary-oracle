package com.literaryoracle;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

@Component
public class ArchiveRepository {
    static final String ARCHIVE_RESOURCE = "archive.json";

    private final List<OracleEntry> entries;

    @Autowired
    public ArchiveRepository(ObjectMapper objectMapper, SupportedLanguageCatalog languageCatalog) {
        this(load(objectMapper, ARCHIVE_RESOURCE), languageCatalog);
    }

    ArchiveRepository(ArchiveDocument document, SupportedLanguageCatalog languageCatalog) {
        if (document == null || document.entries() == null || document.entries().isEmpty()) {
            throw new IllegalStateException("The literary archive must contain entries");
        }
        this.entries = List.copyOf(materialize(document, languageCatalog));
    }

    public List<OracleEntry> entries() {
        return entries;
    }

    static void validate(List<OracleEntry> entries, SupportedLanguageCatalog languageCatalog) {
        Set<Long> ids = new HashSet<>();
        Set<String> originals = new HashSet<>();
        Map<String, Integer> passagesPerWork = new LinkedHashMap<>();
        Set<String> supportedCodes = languageCatalog.supportedCodes();
        for (OracleEntry entry : entries) {
            if (entry == null) throw new IllegalStateException("Archive entries must not be null");
            if (!ids.add(entry.id())) throw invalid(entry, "id must be unique");
            require(entry, entry.passageOriginal(), "passageOriginal");
            require(entry, entry.originalLanguage(), "originalLanguage");
            require(entry, entry.author(), "author");
            require(entry, entry.originalWorkTitle(), "originalWorkTitle");
            require(entry, entry.type(), "type");
            require(entry, entry.publicDomainStatus(), "publicDomainStatus");
            if (!originals.add(normalized(entry.passageOriginal()))) {
                throw invalid(entry, "passageOriginal must be unique");
            }
            String workKey = normalized(entry.author()) + "\u0000"
                    + normalized(entry.originalWorkTitle());
            int workPassages = passagesPerWork.merge(workKey, 1, Integer::sum);
            if (workPassages > 2) {
                throw invalid(entry, "an author/work pair may contain at most two passages");
            }

            if (!languageCatalog.allLanguageCodes().contains(entry.originalLanguage())) {
                throw invalid(entry, "originalLanguage is not declared in supported-languages.json: "
                        + entry.originalLanguage());
            }
            if (entry.themes() == null || entry.themes().isEmpty()
                    || entry.themes().stream().anyMatch(ArchiveRepository::isBlank)) {
                throw invalid(entry, "themes must contain stable non-empty keys");
            }
            if (entry.themes().size() > 3) {
                throw invalid(entry, "themes must contain at most three keys");
            }
            if (entry.themes().stream().distinct().count() != entry.themes().size()) {
                throw invalid(entry, "themes must not contain duplicate keys");
            }
            if (!ThemeDetector.THEME_KEYS.containsAll(entry.themes())) {
                throw invalid(entry, "themes contain a key outside the stable theme vocabulary: "
                        + entry.themes());
            }

            Map<String, LocalizedArchiveContent> localizations = entry.localizations();
            if (localizations == null) throw invalid(entry, "localizations must be present");
            if (!localizations.keySet().equals(supportedCodes)) {
                Set<String> missing = new HashSet<>(supportedCodes);
                missing.removeAll(localizations.keySet());
                Set<String> unexpected = new HashSet<>(localizations.keySet());
                unexpected.removeAll(supportedCodes);
                throw invalid(entry, "localizations must contain exactly the 19 supported codes; missing="
                        + missing + ", unexpected=" + unexpected);
            }
            localizations.forEach((code, content) -> validateLocalization(entry, code, content));
        }
    }

    private static List<OracleEntry> materialize(ArchiveDocument document,
            SupportedLanguageCatalog languageCatalog) {
        if (isBlank(document.translationNote())) {
            throw new IllegalStateException("The literary archive needs a shared translationNote");
        }
        if (document.authors() == null || document.authors().isEmpty()) {
            throw new IllegalStateException("The literary archive needs author biographies");
        }
        document.authors().forEach((author, biography) -> {
            if (isBlank(author) || isBlank(biography)) {
                throw new IllegalStateException("Archive authors need a canonical name and English biography");
            }
        });

        Set<String> supportedCodes = languageCatalog.supportedCodes();
        List<OracleEntry> materialized = document.entries().stream().map(record -> {
            if (record == null) throw new IllegalStateException("Archive records must not be null");
            require(record.id(), record.passageOriginal(), "passageOriginal");
            require(record.id(), record.originalLanguage(), "originalLanguage");
            require(record.id(), record.author(), "author");
            require(record.id(), record.originalWorkTitle(), "originalWorkTitle");
            String biography = document.authors().get(record.author());
            if (isBlank(biography)) {
                throw new IllegalStateException("Invalid archive record " + record.id()
                        + ": author is not declared in the shared author catalog");
            }
            require(record.id(), record.englishWorkTitle(), "englishWorkTitle");
            require(record.id(), record.englishContextNote(), "englishContextNote");
            require(record.id(), record.type(), "type");
            require(record.id(), record.sourceUrl(), "sourceUrl");
            if (!isDirectHttpsUrl(record.sourceUrl())) {
                throw new IllegalStateException("Invalid archive record " + record.id()
                        + ": sourceUrl must be a direct HTTPS URL");
            }
            if (record.year() != null && record.year() == 0) {
                throw new IllegalStateException("Invalid archive record " + record.id()
                        + ": year must not be zero when present");
            }
            require(record.id(), record.publicDomainStatus(), "publicDomainStatus");
            if (record.passages() == null || !record.passages().keySet().equals(supportedCodes)) {
                Set<String> present = record.passages() == null ? Set.of() : record.passages().keySet();
                Set<String> missing = new HashSet<>(supportedCodes);
                missing.removeAll(present);
                Set<String> unexpected = new HashSet<>(present);
                unexpected.removeAll(supportedCodes);
                throw new IllegalStateException("Invalid archive record " + record.id()
                        + ": passages must contain exactly the 19 supported codes; missing="
                        + missing + ", unexpected=" + unexpected);
            }

            Map<String, LocalizedArchiveContent> localizations = new LinkedHashMap<>();
            record.passages().forEach((code, passage) -> {
                require(record.id(), passage, code + ".passage");
                boolean originalText = code.equals(record.originalLanguage())
                        && passage.strip().equals(record.passageOriginal().strip());
                String note = originalText ? "Original text; no translation is used."
                        : document.translationNote();
                localizations.put(code, new LocalizedArchiveContent(passage,
                        record.englishWorkTitle(), record.englishContextNote(), biography, note));
            });

            if (supportedCodes.contains(record.originalLanguage())) {
                String originalPassage = record.passages().get(record.originalLanguage());
                if (!record.passageOriginal().strip().equals(originalPassage.strip())) {
                    throw new IllegalStateException("Invalid archive record " + record.id()
                            + ": passage in the original target language must equal passageOriginal");
                }
            }
            return new OracleEntry(record.id(), record.passageOriginal(), record.originalLanguage(),
                    Map.copyOf(localizations), record.author(), record.originalWorkTitle(),
                    record.year(), record.type(), record.sourceUrl(), List.copyOf(record.themes()),
                    record.publicDomainStatus());
        }).toList();

        validate(materialized, languageCatalog);
        return materialized;
    }

    private static void require(long id, String value, String field) {
        if (isBlank(value)) {
            throw new IllegalStateException("Invalid archive record " + id + ": "
                    + field + " must not be blank");
        }
    }

    private static void validateLocalization(OracleEntry entry, String code,
            LocalizedArchiveContent content) {
        if (content == null) throw invalid(entry, "localization " + code + " is null");
        require(entry, content.passage(), code + ".passage");
        require(entry, content.workTitle(), code + ".workTitle");
        require(entry, content.contextNote(), code + ".contextNote");
        require(entry, content.authorBio(), code + ".authorBio");
        require(entry, content.translationNote(), code + ".translationNote");
    }

    private static void require(OracleEntry entry, String value, String field) {
        if (isBlank(value)) throw invalid(entry, field + " must not be blank");
    }

    private static IllegalStateException invalid(OracleEntry entry, String message) {
        return new IllegalStateException("Invalid archive entry " + entry.id() + ": " + message);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private static boolean isDirectHttpsUrl(String value) {
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null && !uri.getHost().isBlank()
                    && uri.getPath() != null && !uri.getPath().isBlank();
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private static ArchiveDocument load(ObjectMapper objectMapper, String resourceName) {
        ClassPathResource resource = new ClassPathResource(resourceName);
        try (InputStream input = resource.getInputStream()) {
            return objectMapper.readValue(input, ArchiveDocument.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load classpath:" + resourceName, exception);
        }
    }

    public record ArchiveDocument(
            String translationNote,
            Map<String, String> authors,
            List<ArchiveRecord> entries) {
    }

    public record ArchiveRecord(
            long id,
            String passageOriginal,
            String originalLanguage,
            Map<String, String> passages,
            String author,
            String originalWorkTitle,
            String englishWorkTitle,
            Integer year,
            String type,
            String sourceUrl,
            List<String> themes,
            String publicDomainStatus,
            String englishContextNote) {
    }
}
