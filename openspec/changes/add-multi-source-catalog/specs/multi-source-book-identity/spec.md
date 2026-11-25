## Requirements

### Requirement: Internal book identity

The catalog SHALL assign every logical book a stable internal `book_id` that is independent of all source site identifiers.

#### Scenario: Same book has different source IDs

- **WHEN** two confirmed source records have different external IDs but the same uniquely matched title and author
- **THEN** the catalog SHALL expose one `book_id` with two source bindings

### Requirement: Source-scoped external identity

The catalog MUST treat `(source_key, external_book_id)` as the unique external identity and MUST NOT use `external_book_id` alone as a primary key.

#### Scenario: Two sources reuse an external ID

- **WHEN** two enabled sources both publish external book ID `407`
- **THEN** the catalog SHALL store two independent source records and SHALL NOT overwrite either record

### Requirement: Ambiguous matching

The catalog SHALL keep a source record unresolved when normalized title and author matching produces zero or multiple plausible internal books.

#### Scenario: Same title with different authors

- **WHEN** a source record shares a title with multiple internal books but its author does not uniquely match
- **THEN** the catalog SHALL return an ambiguity state and SHALL NOT auto-link the source record
