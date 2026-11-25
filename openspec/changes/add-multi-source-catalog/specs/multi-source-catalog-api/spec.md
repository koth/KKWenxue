## Requirements

### Requirement: Internal-ID API

The catalog API SHALL accept internal `book_id` for book, manifest, chapter, version and update-plan operations.

#### Scenario: Fetch a chapter from a selected source

- **WHEN** the client requests an internal book and an explicit source key
- **THEN** the API SHALL resolve the source binding and return the chapter only if the binding belongs to that internal book

### Requirement: Legacy source disambiguation

The compatibility API MUST require a source key when addressing a legacy external book ID.

#### Scenario: Legacy request omits source

- **WHEN** a client requests a numeric external ID without a source key
- **THEN** the API SHALL return a structured `source_required` error and SHALL NOT guess a source
