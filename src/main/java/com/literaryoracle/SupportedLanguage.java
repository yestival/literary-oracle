package com.literaryoracle;

public record SupportedLanguage(String code, String name, String direction) {
    /**
     * English label used by the fixed-English application shell. The native
     * {@link #name()} remains available for language-choice options.
     */
    public String englishName() {
        return switch (code) {
            case "en" -> "English";
            case "zh-Hans" -> "Simplified Chinese";
            case "zh-Hant" -> "Traditional Chinese";
            case "ja" -> "Japanese";
            case "ko" -> "Korean";
            case "es" -> "Spanish";
            case "fr" -> "French";
            case "de" -> "German";
            case "it" -> "Italian";
            case "pt" -> "Portuguese";
            case "ru" -> "Russian";
            case "sv" -> "Swedish";
            case "ar" -> "Arabic";
            case "hi" -> "Hindi";
            case "bn" -> "Bengali";
            case "id" -> "Indonesian";
            case "tr" -> "Turkish";
            case "vi" -> "Vietnamese";
            case "th" -> "Thai";
            case "lzh" -> "Literary Chinese";
            case "fa" -> "Persian";
            case "grc" -> "Ancient Greek";
            case "la" -> "Latin";
            default -> name;
        };
    }
}
