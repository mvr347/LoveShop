# Caravan patches (обход лимита GitHub API на большие файлы)

Валидные `git apply` патчи. После apply исходники на ветке полные — можно билдить и открывать PR.

```bash
git fetch origin
git checkout feature/caravan-commission-rework
git pull
bash docs/patches/apply-all.sh
git add -A
git commit -m "feat(caravan): deposit gate, empty leave, force start, heads"
git push
```

Проверка:
```bash
grep -n 'forceStart\|leave-if-empty\|Меню лотов' src/main/java/dev/lovelace/loveshops/managers/LostCaravanManager.java
grep -n forceStart src/main/java/dev/lovelace/loveshops/commands/LoveShopsAdminCommand.java
grep CARAVAN_LOST src/main/java/dev/lovelace/loveshops/textures/HeadTextures.java
mvn -q -DskipTests package
```
