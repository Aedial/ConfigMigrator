# Config Migrator

A Minecraft 1.12.2 mod that helps migrate modified configuration values from an instance to another instance, ensuring that your custom settings are preserved when updating a modpack or switching between different modpacks.

## Features
- Keep a record of modified configuration values.
- You should run the mod at least once in the new instance before changing any config values to ensure all default configs are captured. This also means that it should be run once after installing any new mod before modifying said mod's configs, to ensure all default configs are captured.
- To migrate, simply copy the `configs.json` file to the new instance's `minecraft/` directory, like you would with `options.txt`.
- Supported config file formats: `.cfg`, `.toml`, `.properties`, and `.conf`. `.json` files are not supported due to often being used for storing quests or structures, rather than mod configuration.

## Q&A
### How do I migrate my config values to a new modpack or instance?
1. After modifying config values in your current instance, locate the `configs.json` file in the `minecraft/` directory.
2. Copy the `configs.json` file to the `minecraft/` directory of the new modpack or instance.
3. Launch the new instance with Config Migrator installed. The mod will automatically apply your modified config values.

### Where do I put the mod?
Place the Config Migrator mod jar file into the `mods/` folder of your Minecraft instance, both on client and server sides.

### What if a mod updates and changes its config structure?
Config Migrator attempts to match config keys based on their paths and names. However, if a mod significantly changes its config structure (like renaming keys or moving them to different files), some modified values may not be applied. In such cases, you may need to manually adjust the configs.

### How often does the mod check for config changes?
By default, the mod updates within 30 seconds of a change, or every 10 minutes. You can also use the `/cmforceupdate` command to force an immediate update.

### I messed up, how can I reset the defaults?
Simply delete the content of minecraft/config/configmigrator/. Do note it will lose track of your modified configs as well, considering them as default now.

## Building
Run:
```
./gradlew -q build
```
Resulting jar will be under `build/libs/`.

## License
This project is licensed under the MIT License - see the LICENSE file for details.
