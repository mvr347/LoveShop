# LoveShop — notes for Claude Code

## Planned feature (not yet implemented): "Vintage auctions" + auctioneer rarity intake + buyer price balance

Requested by the server owner (2026-08-10), in one message covering three related asks. Spans
**LoveShop** and **LoveBrew** for the auction-intake part (mirrored note in `LoveBrew/CLAUDE.md`).

### 1. Take top-tier LoveBrew beverages into the Auctioneer

Full spec is in `LoveBrew/CLAUDE.md` — summary: 5-6★, long-aged beverages should go to the
Auctioneer (`AuctionManager.createAuction(ItemStack item, int startingPrice)`,
`src/main/java/dev/lovelace/loveshops/managers/AuctionManager.java:32`) for real player bidding
instead of LoveBrew's Innkeeper paying a formula price. LoveShop already has an analogous pattern
for its own Buyer NPC worth reusing: `BuyerManager.processSale`
(`src/main/java/dev/lovelace/loveshops/managers/BuyerManager.java:93`) checks
`basePrice * quantity >= auctioneer.price-threshold` (config) to route pricier items to an
`"auction"` channel instead of `"seller"`, and `AuctionManager.createAuctionsFromPendingItems()`
(`AuctionManager.java:84`) turns those pending rows into live auctions. LoveBrew doesn't currently
depend on LoveShop (or vice versa) — needs a soft-dependency.

### 2. Route rare items into the auctioneer too

Currently `PriceCalculator.getBasePrice(item)` (`managers/PriceCalculator.java:31`) prices purely
by `Material` name via `prices-config.<MATERIAL>` in config.yml (plus a special case for LoveClans
artifacts) — there's no rarity or enchantment awareness at all. The owner wants items that are
epic/legendary-tier (the purple/gold-ish vanilla rarity colors) or armor/weapons carrying
enchantments to also end up on the Auctioneer instead of being priced as a flat per-material
buyout. Bukkit 1.20.5+ exposes `ItemStack`/`ItemMeta` rarity (`org.bukkit.inventory.ItemRarity`:
COMMON/UNCOMMON/RARE/EPIC) — verify this API is actually available on this project's Paper
version before relying on it, and combine with an enchantment check (`ItemMeta.hasEnchants()`)
for gear. This needs the same pending-items → auction plumbing as part 1.

### 3. Balance buyer (Скупщик) prices — they pay too much

The owner explicitly said the Buyer NPC currently gives too many coins. Final price is
`PriceCalculator.calculateBuyPrice` (`PriceCalculator.java:175`):
`basePrice × (1 + variance% − penalty% + reputationBonus%) × amount`. To fix, review and likely
lower:
- `prices-config.<MATERIAL>` values in `config.yml` (the base prices themselves — most likely
  candidate, since nothing else here looks obviously broken).
- `buyer.price-variance.min-percent` / `max-percent` (currently -30%/+30% by default per the code) —
  maybe the range or its center is too generous.
- Whatever config key backs `getReputationBonusPercent(player)` — check it isn't stacking too
  favorably with variance.
- `buyer.repetition-penalty.*` — confirm the penalty is actually biting for repeat sales of the
  same item type (it's meant to discourage price-farming one material repeatedly).

### Open questions for whoever picks this up

- Exact quality/rarity thresholds for auction-routing (both the LoveBrew beverage side and the
  rare-item side here).
- Target price levels for the buyer rebalance — needs a concrete "too high by how much" from the
  owner, or a reasonable judgment call with the owner able to redirect after seeing it in-game.
