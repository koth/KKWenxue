# Capability: online-library-sync

## ADDED Requirements


### Requirement: Sync SHALL compare manifests before fetching bodies

The App SHALL compare the remote manifest revision and chapter references before scheduling chapter body work. An unchanged manifest SHALL not trigger body downloads.

#### Scenario: An online book has no remote changes

- WHEN the App checks the remote manifest and its revision matches the local manifest
- THEN the App marks the book up to date
- AND it does not download the book or any chapter body

### Requirement: Sync SHALL update only changed chapter references and caches

When a manifest changes, the App SHALL add new references, update changed titles or hashes, remove deleted references, and invalidate only affected chapter bodies. Unchanged chapter caches SHALL remain usable.

#### Scenario: A book gains new chapters

- WHEN the remote manifest adds chapters after the local last chapter
- THEN the App updates the directory and marks only the new chapters as uncached
- AND existing cached chapters remain readable without redownload

### Requirement: Background work SHALL be persistent and resumable

Prefetch, range-cache, and manifest refresh work SHALL survive App process death and resume from validated cache state. The scheduler SHALL use bounded concurrency, network constraints, retry backoff, and cancellation.

#### Scenario: The App is killed during prefetch

- WHEN the process stops after some chapters have completed and others are pending
- THEN completed chapter bodies remain available
- AND the next run resumes pending work without re-downloading validated bodies

### Requirement: The App SHALL provide explicit cache scope controls

The App SHALL support current-chapter reading, adjacent-chapter prefetch, a user-selected chapter range, and an explicit full-book cache/export action. Full-book caching SHALL be opt-in.

#### Scenario: User starts a full-book cache

- WHEN the user explicitly chooses to cache the full book
- THEN the App creates a background task with visible progress, pause, resume, retry, and failure-chapter information
- AND normal directory browsing and current-chapter reading remain available

### Requirement: Sync failures SHALL be local to the affected work

A failed manifest refresh SHALL preserve the last valid directory. A failed prefetch or chapter request SHALL not make the book unreadable or cancel unrelated completed work.

#### Scenario: The remote service is temporarily unavailable

- WHEN a manifest or prefetch request fails after retries
- THEN the App records the failure and offers retry
- AND the last valid manifest and all validated chapter caches remain usable
