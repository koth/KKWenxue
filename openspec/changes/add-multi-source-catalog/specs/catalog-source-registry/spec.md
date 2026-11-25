## Requirements

### Requirement: Isolated source synchronization

Each source SHALL have independent adapter configuration, cursor, health state, retry state and failure history.

#### Scenario: One source redirects to an unrelated site

- **WHEN** a source request redirects to Google or another unconfigured host
- **THEN** the source SHALL enter an error state and no book or chapter metadata from that response SHALL be published

### Requirement: Source freshness

The catalog SHALL expose the last successful check, last update time and latest failure for every book source binding.

#### Scenario: Preferred source becomes stale

- **WHEN** the preferred source has repeated failures and a confirmed alternate source has a newer successful update
- **THEN** the catalog SHALL recommend the alternate source without changing the internal `book_id`
