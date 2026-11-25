# Capability: online-library-catalog

## ADDED Requirements


### Requirement: Fresh installations SHALL start with an empty reading shelf

The App SHALL ship without bundled books, bundled reader SQLite data, or first-run seed-copy logic. A fresh installation SHALL create an empty local reading database.

#### Scenario: First launch has no imported books

- WHEN a fresh installation starts before the user imports a book
- THEN the shelf contains zero reading books and shows an entry point to search the online catalog
- AND the App does not open a sample book or use a fallback book ID

### Requirement: Reading books SHALL come from the online catalog

The App SHALL add a readable book only after a successful online catalog import. The TXT/EPUB local-reading import path SHALL be unavailable.

#### Scenario: User opens the shelf import action

- WHEN the user opens the shelf on an empty or non-empty App
- THEN the primary import action opens online catalog search
- AND no local file picker is offered for TXT or EPUB reading books

### Requirement: Online import SHALL persist the manifest before any full-body download

The online import flow SHALL save book metadata, remote revision, chapter references, and chapter order as soon as the manifest is validated. It MUST NOT download or parse the complete book before publishing the shelf entry.

#### Scenario: User imports a large online book

- WHEN the catalog returns a valid manifest for a large book
- THEN the App publishes the book and its available chapter directory locally
- AND the user can open the directory while full chapter bodies remain uncached

### Requirement: The catalog manifest SHALL identify chapters independently of display numbers

Each chapter reference SHALL contain a stable source chapter identity, title, physical order, and manifest revision. Display chapter numbers SHALL NOT be used as the cross-book identity.

#### Scenario: Two chapters share a display number

- WHEN the manifest contains two source chapters with the same displayed number
- THEN both references remain in physical order with distinct stable IDs
- AND selecting one does not address the other

### Requirement: Empty library surfaces SHALL be safe and actionable

Home, shelf, audio, synthesis, bookmarks, and resume surfaces SHALL handle an empty book list without indexing the first book or opening an invalid book ID.

#### Scenario: User opens the App with an empty shelf

- WHEN the user opens the home, shelf, or audio surface with no imported books
- THEN the surface shows an empty-state action that leads to online catalog search
- AND no crash, fake progress, or automatic reading session occurs
