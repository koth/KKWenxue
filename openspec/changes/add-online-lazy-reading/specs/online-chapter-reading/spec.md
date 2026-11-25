# Capability: online-chapter-reading

## ADDED Requirements


### Requirement: The reader SHALL load only the requested chapter body

The reader SHALL resolve the current chapter through memory cache, local chapter cache, or the catalog chapter endpoint. It SHALL NOT require a complete-book ZIP or all chapter bodies before opening the current chapter.

#### Scenario: User opens an uncached chapter

- WHEN the selected chapter has a valid manifest reference but no local body
- THEN the reader shows the chapter title and a local loading state
- AND it requests only that chapter body
- AND it opens the chapter after the body hash and revision validate

### Requirement: Chapter body responses SHALL be versioned and verifiable

Each chapter response SHALL include the book ID, stable chapter ID, remote revision, normalized body, byte count, and SHA-256. The App SHALL reject a body whose identity, revision, size, or hash does not match the request.

#### Scenario: A stale or corrupted chapter response arrives

- WHEN the response revision or SHA-256 does not match the local chapter reference
- THEN the App discards the response and marks only that chapter as failed
- AND the user can retry the chapter without losing other cached chapters

### Requirement: Cached chapters SHALL be readable offline

A successfully validated chapter body SHALL be stored in the local chapter cache independently of the full book. The reader SHALL open cached chapters without network access.

#### Scenario: Network is unavailable after a chapter was cached

- WHEN the user opens a previously cached chapter while offline
- THEN the reader opens the cached body
- AND it does not block on a catalog request

### Requirement: Current reading SHALL take priority over prefetch work

Foreground requests for the visible chapter SHALL take priority over background prefetch and full-book cache jobs. Background work SHALL NOT block page layout or chapter navigation.

#### Scenario: Prefetch is active while the user changes chapters

- WHEN a prefetch job is downloading another chapter and the user opens a different uncached chapter
- THEN the current chapter request is scheduled ahead of the prefetch
- AND the reader can show its loading state without waiting for the background job to finish

### Requirement: The reader SHALL expose chapter-level failures

A failed chapter request SHALL show a chapter-scoped error and retry action. It SHALL NOT mark the whole book as failed or remove already cached chapters.

#### Scenario: The source chapter temporarily fails

- WHEN the catalog cannot retrieve or validate the selected chapter
- THEN the reader shows a retry action for that chapter
- AND previously cached chapters and the directory remain available
