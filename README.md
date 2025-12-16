# Config Migrator

A Minecraft 1.12.2 mod that helps migrate modified configuration values from an instance to another instance, ensuring that your custom settings are preserved when updating a modpack or switching between different modpacks.

## Features
- Keep a record of modified configuration values.
- You should launch a new instance before changing any config file manually to ensure all default configs are captured. This also means that it should be run once after installing any new mod before modifying said mod's configs, to ensure its default configs are captured as well.
- To migrate, simply copy the `configs.json` file to the new instance's `minecraft/` directory, like you would with `options.txt`.
- Supported config file formats: `.cfg`, `.toml`, `.properties`, and `.conf`. `.json` files are not supported due to often being used for storing quests or structures, rather than mod configuration.

## FAQ
### How do I migrate my config values to a new modpack or instance?
1. After modifying config values in your current instance, locate the `configs.json` file in the `minecraft/` directory.
2. Copy the `configs.json` file to the `minecraft/` directory of the new modpack or instance.
3. Launch the new instance with Config Migrator installed. The mod will automatically apply your modified config values.
4. Restart the game to ensure all changes take effect, as Forge loads configs before Config Migrator is allowed to modify them. They will be applied on disk, but not in memory until a restart. Some mods may reload their configs after the migration, but it is not guaranteed.

### Where do I put the mod?
Place the Config Migrator mod jar file into the `mods/` folder of your Minecraft instance, both on client and server sides.

### What if a mod updates and changes its config structure?
Config Migrator attempts to match config keys based on their paths and names. However, if a mod significantly changes its config structure (like renaming keys or moving them to different files), some modified values may not be applied. In such cases, you may need to manually adjust the configs.

Deleted or missing values will not be saved in `configs.json`, so keeping backups of the file before moving between instances is recommended.

### How often does the mod check for config changes?
By default, the mod detects any change within 30s of a change, and also does a check upon exit. The process itself is lightweight, taking 100-200ms on average for huge modpacks (400+ configs, megabytes of text).

### I messed up, how can I reset the defaults?
Simply delete the content of minecraft/config/configmigrator/. Do note that it will lose track of your modified configs as well, considering them as current default. You should restore the configs to their original default values, before doing so, if you want to track modifications again, or manually edit defaults.zip to restore the original defaults.

## Configuration (configmigrator.cfg)

Config Migrator has a small config file `config/configmigrator.cfg` with one main option: `ignoredKeys`.

- `ignoredKeys` (list): keys to ignore when detecting and saving modifications. Use it for configs that change often but are useless to migrate, like timestamps, cached data, or version checks.

Wildcard patterns are supported:

- `prefix.*` matches any key starting with `prefix.` (e.g. `cache.*`)
- `*.suffix` matches any key ending with `.suffix` (e.g. `*.lastCheck`)
- `prefix.*.suffix` supports matching keys with variable middle sections

Examples:

```
general {
    S:ignoredKeys <
        cache.S:lastCheck
        cache.S:digest
        module.versioncheck.general.I:versionSave
    >
}
```

## Building
Run:
```
./gradlew -q build
```
Resulting jar will be under `build/libs/`.

## License
This project is licensed under the MIT License - see the LICENSE file for details.
