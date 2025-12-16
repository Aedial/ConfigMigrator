# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog and this project adheres to Semantic Versioning.

- Keep a Changelog: https://keepachangelog.com/en/1.1.0/
- Semantic Versioning: https://semver.org/spec/v2.0.0.html


## [1.1.0] - 2025-12-11
### Fixed
- Fix file only saving things from latest restart.
- Fix saving even when no changes were made since last save.
- Fix empty default values being ignored when saving modifications.

### Added
- Add a config to ignore specific keys when tracking modifications, to avoid saving useless or frequently changing values like timestamps or cached data.

### Changed
- Remove the /cmforceupdate command, as the save-on-exit and periodic update mechanisms are sufficient for most use cases.
- Remove the metadata file used to track last saved state, and instead rely on an initial scan at startup to determine modified configs. This simplifies the implementation and reduces failure points.


## [1.0.0] - 2025-12-10
### Added
- Implement defaults storage in ZIP format for improved efficiency and reduced disk space usage.
- Implement configs.json migration file to store modified configuration values.
- Implement config tracking for supported file formats: .cfg, .toml, .properties, and .conf.
- Add /cmforceupdate command to force immediate config update.
