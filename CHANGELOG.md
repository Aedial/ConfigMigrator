# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog and this project adheres to Semantic Versioning.

- Keep a Changelog: https://keepachangelog.com/en/1.1.0/
- Semantic Versioning: https://semver.org/spec/v2.0.0.html


## [1.0.0] - 2025-12-10
### Added
- Implement defaults storage in ZIP format for improved efficiency and reduced disk space usage.
- Implement configs.json migration file to store modified configuration values.
- Implement config tracking for supported file formats: .cfg, .toml, .properties, and .conf.
- Add /cmforceupdate command to force immediate config update.
