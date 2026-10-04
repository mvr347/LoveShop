package dev.lovelace.loveshops.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HintsTest {

    @Test
    void coinPickerIsOneLine() {
        assertEquals("<yellow>Shift</yellow> <gray>— сменить</gray> <dark_gray>·</dark_gray> "
                + "<green>ЛКМ</green> <gray>+</gray> <dark_gray>·</dark_gray> <red>ПКМ</red> <gray>\u2212</gray>",
                Hints.coinPicker());
    }

    @Test
    void amountStepperShowsBigStep() {
        assertEquals("<green>ЛКМ</green> <gray>+1</gray> <dark_gray>·</dark_gray> "
                + "<red>ПКМ</red> <gray>\u22121</gray> <dark_gray>·</dark_gray> <yellow>Shift</yellow> <gray>\u00d78</gray>",
                Hints.amountStepper(8));
    }

    @Test
    void customKeyIsAqua() {
        assertEquals("<aqua>Q</aqua> <gray>— выбросить</gray>", Hints.act("Q", "выбросить"));
    }
}
