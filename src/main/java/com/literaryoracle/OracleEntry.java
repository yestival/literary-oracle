package com.literaryoracle;

import java.util.List;
import java.util.Map;

public record OracleEntry(
        long id,
        String passageOriginal,
        String originalLanguage,
        Map<String, LocalizedArchiveContent> localizations,
        String author,
        String originalWorkTitle,
        Integer year,
        String type,
        String sourceUrl,
        List<String> themes,
        String publicDomainStatus) {
}
