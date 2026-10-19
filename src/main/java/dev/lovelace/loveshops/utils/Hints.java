package dev.lovelace.loveshops.utils;

import dev.lovelace.loveshops.utils.LoreBlocks.Click;
import dev.lovelace.loveshops.utils.LoreBlocks.Hint;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Click hints that several menus share (price picker, amount stepper), built on {@link LoreBlocks}: a separator and
 * one click per line, ПКМ gold. The action words come from lang.yml ({@code market.gui-hint-*}) through {@code text},
 * so nothing here is hard-coded Russian.
 */
public final class Hints {

    private Hints() {}

    /** Price / coin picker: Shift+ЛКМ switches the coin, ЛКМ adds one, ПКМ takes one away. */
    public static List<String> coinPicker(LoreBlocks.Style style, UnaryOperator<String> text) {
        return LoreBlocks.clicks(style,
                new Hint(Click.SHIFT_LEFT, text.apply("gui-hint-coin-cycle")),
                new Hint(Click.LEFT, text.apply("gui-hint-coin-add")),
                new Hint(Click.RIGHT, text.apply("gui-hint-coin-sub")));
    }

    /** Amount stepper: ЛКМ +1, ПКМ −1 and the big step on Shift ({@code {step}} in the text is the step size). */
    public static List<String> amountStepper(LoreBlocks.Style style, UnaryOperator<String> text, int bigStep) {
        String step = String.valueOf(bigStep);
        return LoreBlocks.clicks(style,
                new Hint(Click.LEFT, text.apply("gui-hint-amount-add")),
                new Hint(Click.RIGHT, text.apply("gui-hint-amount-sub")),
                new Hint(Click.SHIFT_LEFT, text.apply("gui-hint-amount-add-big").replace("{step}", step)),
                new Hint(Click.SHIFT_RIGHT, text.apply("gui-hint-amount-sub-big").replace("{step}", step)));
    }
}
