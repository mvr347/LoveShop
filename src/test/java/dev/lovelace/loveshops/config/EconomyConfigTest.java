package dev.lovelace.loveshops.config;

import dev.lovelace.lovecore.api.economy.MoneyParser;
import dev.lovelace.loveshops.market.UpgradeMath;
import dev.lovelace.loveshops.utils.Money;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Every money key of the default config must parse: a typo must fail the build, not silently change a price. */
class EconomyConfigTest {

    private static YamlConfiguration load(String resource) throws Exception {
        try (Reader r = new InputStreamReader(EconomyConfigTest.class.getResourceAsStream("/" + resource), StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(r);
        }
    }

    private static long money(YamlConfiguration cfg, String path) {
        Object raw = cfg.get(path);
        assertNotNull(raw, path);
        if (raw instanceof Number n) return n.longValue();
        return MoneyParser.parse(String.valueOf(raw), MoneyParser.STANDARD);
    }

    @Test
    void moneyKeysParseToTheDocumentedValues() throws Exception {
        YamlConfiguration cfg = load("config.yml");
        assertEquals(6_000L, money(cfg, "prices-config.artifacts.default-price"));
        assertEquals(400L, money(cfg, "wanderer.deal.cost"));
        assertEquals(3_000L, money(cfg, "market.feudal.default-rent-price"));
        assertEquals(1_000L, money(cfg, "market.stalls.confirm-threshold"));
        assertEquals(2_000L, money(cfg, "market.stalls.upgrade-cost"));
        assertEquals(2_000L, money(cfg, "market.stalls.upgrade-cost-step"));
        assertEquals(1_000L, money(cfg, "market.rating.min-trade-amount"));
        assertEquals(200L, money(cfg, "market.guard.cost-per-day"));
        assertEquals(100L, money(cfg, "market.price.start-unit"));
        assertEquals(800L, money(cfg, "caravan.lost.starting-price"));
        assertEquals(2_500L, money(cfg, "caravan.lost.secret-starting-price"));
        assertEquals(100L, money(cfg, "caravan.lost.entry-fee.amount"));
        assertEquals(100_000_000L, money(cfg, "commission.max-lot-price"));
    }

    @Test
    void legacyKeysAreGone() throws Exception {
        YamlConfiguration cfg = load("config.yml");
        assertFalse(cfg.contains("market.stalls.upgrade-cost-coins"));
        assertFalse(cfg.contains("market.stalls.upgrade-cost-step-coins"));
        assertFalse(cfg.contains("caravan.lost.entry-fee.item"));
    }

    @Test
    void buyerAndMerchantsStaySane() throws Exception {
        YamlConfiguration cfg = load("config.yml");
        assertEquals("MODEL", cfg.getString("buyer.price-source"));
        double payout = cfg.getDouble("buyer.payout-percent");
        // the Buyer pays less than any merchant charges: buying from a merchant and selling to the Buyer is never a profit
        assertTrue(payout > 0 && payout < cfg.getDouble("war-merchant.markup-percent"));
        assertTrue(payout < cfg.getDouble("wanderer.markup-percent"));
        assertTrue(payout < cfg.getDouble("caravan.daily.markup-percent"));
        assertTrue(cfg.getDouble("economy.migration.money-factor") > 0);
        assertTrue(cfg.getDouble("economy.migration.price-factor") > 0);
    }

    @Test
    void upgradeLadderMatchesTheDocumentedTotal() {
        long total = 0;
        for (int level = 1; level <= 16; level++) {
            total += UpgradeMath.linearCost(level, 1L, 2_000L, 2_000L);
        }
        assertEquals(272_000L, total);
    }

    @Test
    void shippedPricesFileIsAnEmptyOverrideList() throws Exception {
        YamlConfiguration prices = load("prices.yml");
        assertEquals(2, prices.getInt("scale-version"));
        assertTrue(prices.getConfigurationSection("buyer").getKeys(false).isEmpty());
        assertTrue(prices.getConfigurationSection("war_merchant").getKeys(false).isEmpty());
        assertTrue(prices.getConfigurationSection("wanderer").getKeys(false).isEmpty());
    }

    @Test
    void percentOfRoundsAndNeverReturnsZeroForPricedItems() {
        assertEquals(55L, Money.percentOf(100, 55));
        assertEquals(1L, Money.percentOf(1, 10)); // a priced item never pays nothing
        assertEquals(0L, Money.percentOf(0, 55));
        assertEquals(0L, Money.percentOf(100, Double.NaN));
        assertEquals(0L, Money.percentOf(100, -5));
    }
}
