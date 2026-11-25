## Requirements

### Requirement: Source chapter mapping

The catalog SHALL store internal chapter identity separately from source chapter identity and SHALL address正文缓存 by source binding, external chapter ID and source revision.

#### Scenario: Source chapter IDs differ

- **WHEN** two confirmed sources expose the same logical chapter with different external chapter IDs
- **THEN** the catalog MAY map both records to one internal `chapter_id` while retaining both external IDs and separate body hashes

### Requirement: Chapter ambiguity safety

The catalog MUST leave chapter mappings unresolved when title, order or neighboring context produces multiple candidates or a conflict.

#### Scenario: Duplicate chapter titles

- **WHEN** a source contains two chapters with the same title and no unique neighboring context
- **THEN** the catalog SHALL preserve both source chapters and SHALL NOT overwrite an existing confirmed chapter mapping
