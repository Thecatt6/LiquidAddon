# Shelf: UI code, set aside on purpose

Everything in this folder is **not compiled and not registered**: it lives outside the Gradle
source sets (`src/main/kotlin`, `src/test/kotlin`), so the add-on builds and ships without any of
it. It was moved here from `src` on purpose, keeping the code available for a later decision.

- `ArrayListLayout.kt` + `ArrayListLayoutTest.kt` + `ModuleAddonList.kt` — the themed array-list of
  enabled modules ("hacks list"): sidebar-free overlay, teal gradient names, grey `Mode` suffix.
- `VapeLayout.kt` + `VapeLayoutTest.kt` + `ModuleAddonClickGui.kt` — the Vape-style click GUI with
  in-card settings and its geometry.

To bring either back: move the three files of one group into their old packages
(`com.liquidaddon.ui` and `com.liquidaddon.modules`, tests under `src/test/kotlin/com/liquidaddon/ui`),
re-add the two objects to `Addon.registerModules`, and re-run the pure-test gate. The layouts are
pure classes; their tests travel with them.
