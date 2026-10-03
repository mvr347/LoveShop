package dev.lovelace.loveshops.utils;

import dev.lovelace.lovecore.api.economy.Denomination;
import dev.lovelace.lovecore.api.economy.LoveEconomy;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CoinFormatTest {

    private static final List<Denomination> DENS = List.of(
            new Denomination("ia:copper_coin", 1),
            new Denomination("ia:iron_coin", 10),
            new Denomination("ia:gold_coin", 50),
            new Denomination("ia:diamond_coin", 100),
            new Denomination("ia:netherite_coin", 1000)
    );

    private static final LoveEconomy MOCK_ECO = (LoveEconomy) Proxy.newProxyInstance(
            LoveEconomy.class.getClassLoader(),
            new Class<?>[]{LoveEconomy.class},
            (proxy, method, args) -> {
                if ("denominations".equals(method.getName())) return DENS;
                if ("currencyName".equals(method.getName())) return "Монеты";
                return null;
            }
    );

    @Test
    void testGlyphExtraction() {
        assertEquals("%img_copper_coin%", CoinFormat.getCoinGlyph(new Denomination("ia:copper_coin", 1)));
        assertEquals("%img_gold_coin%", CoinFormat.getCoinGlyph(new Denomination("voidcore:gold_coin", 50)));
        assertEquals("%img_copper_coin%", CoinFormat.getCoinGlyph(null));
    }

    @Test
    void testZeroAndNegativeAmount() {
        assertEquals("%img_copper_coin% &f x0", CoinFormat.formatGlyphs(MOCK_ECO, 0));
        assertEquals("%img_copper_coin% &f x0", CoinFormat.formatGlyphs(MOCK_ECO, -10));
    }

    @Test
    void testFormatSplit() {
        // 1255 = 1x1000 (netherite) + 2x100 (diamond) + 1x50 (gold) + 5x1 (copper)
        String formatted = CoinFormat.formatGlyphs(MOCK_ECO, 1255);
        assertEquals("%img_netherite_coin% &f x1  %img_diamond_coin% &f x2  %img_gold_coin% &f x1  %img_copper_coin% &f x5", formatted);
    }
}
