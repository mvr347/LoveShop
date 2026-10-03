# Caravan patches (base64 — обход лимита API)

Патчи в `.b64` (ASCII), скрипт декодирует и делает `git apply`.

```bash
git fetch origin && git checkout feature/caravan-commission-rework && git pull
bash docs/patches/apply-all.sh
git add -A
git commit -m "feat(caravan): deposit gate, empty leave, force start, heads"
git push
```

После этого исходники на ветке полные → PR → Merge → Build → Use.
