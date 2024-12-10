# Cartridge - A Paper fork, using paperweight

Mine in Abyss' feature fork of [Leaf](https://github.com/Winds-Studio/Leaf/), a PaperMC fork optimized for performance. We add feature that are hard to code using plugins, namely:

- Forcing falldamage in water, slime blocks, etc...
- Disabling some vanilla features like allay AI which slow down our flying mobs

## Usage guide

### Building

- `gradle applyPatches` to apply the latest tracked patches to your local project
- `gradle createMojmapPaperclipJar` to build and test your server jar
- `gradle rebuildPatches` to update patches after your local changes

### Updating

- Update `leafCommit` in `gradle.properties` to the latest commit hash
- Run `applyPatches`
  - If errors occur, read the failed patch, right click it in IntelliJ and click `Apply Patch`
  - Often IntelliJ will resolve conflicts itself, and the rest you can manually do using its nice UI
  - Once done, do not commit, instead:
```bash
cd cartridge-server
git add .
git am --continue
```
  - Repeat for any other failing patches
