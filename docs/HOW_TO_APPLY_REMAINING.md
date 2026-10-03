# Как применить остаток ТЗ (bossbar, PriceGui, quality)

Код готов локально. Из-за лимита GitHub Contents API большие `.java` заливаются так:

```bash
cd LoveShop
git checkout feature/caravan-commission-rework && git pull

# Вариант A: патч из артефактов агента
export TZ_PATCH=/path/to/tz-complete.patch
bash docs/patches/apply-tz-complete.sh

# Вариант B: Git Database API (нужен токен)
# 1) apply патч локально
# 2) export GH_TOKEN=ghp_...
# 3) python3 scripts/git-database-push-caravan.py

git status
mvn -q -DskipTests package
git add -A
git commit -m "feat: caravan bossbar, commission PriceGui, crate quality"
git push
```

После push исходники на remote полные → PR → Merge → Build.
