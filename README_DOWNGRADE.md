# Project Zomboid Build 42 Engine Optimizer (PZO) - Version Downgrader

This standalone utility allows you to roll back your PZO installation to any of the last 5 stable releases if you encounter compatibility issues with specific mods or game updates.

---

## How to Use

### Windows
1. Ensure Project Zomboid is closed.
2. Double-click `downgrade.bat`.
3. Choose the version number you wish to install from the menu.

### Linux / SteamOS / Steam Deck
1. Ensure Project Zomboid is closed.
2. In Desktop Mode or terminal, run:
   ```bash
   chmod +x pzo_downgrade.sh
   ./pzo_downgrade.sh
   ```
3. Follow the on-screen prompts.

### macOS
1. Ensure Project Zomboid is closed.
2. Double-click `pzo_downgrade.command`.
3. Select the desired release from the menu. The tool will automatically download the files and re-sign your `Project Zomboid.app` bundle.

---

## Safety & Backups
- All existing engine files and configuration files are automatically backed up to `Installer_Backups/Downgrade_<timestamp>` prior to replacing any files.
- You can upgrade back to the latest version at any time using the main `install.bat` / `pzo_optimizer.sh` installer.
