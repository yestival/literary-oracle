package com.literaryoracle;

public record LocalizedArchiveContent(
        String passage,
        String workTitle,
        String contextNote,
        String authorBio,
        String translationNote) {
}
