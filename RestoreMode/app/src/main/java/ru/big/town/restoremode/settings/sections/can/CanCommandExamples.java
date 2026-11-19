package ru.big.town.restoremode.settings.sections.can;

public final class CanCommandExamples {
    private CanCommandExamples() {}

    public static final String[][] COMMANDS = {
        {"64 08 80 00 00 00 00 00 00 03", "обогрев руля вкл"},
        {"64 08 40 00 00 00 00 00 00 03", "обогрев руля выкл"},
        // IVI_CONTROL (0x65) packs several one-shot fields into one frame: the rear-defog key is
        // bits 36-37 (2 on, 1 off), bits 22-23 and 30-31 are the markers the OEM service always
        // writes as 3, and bits 16-17 are BATTERY_TEP_CONTROL_SWITCH (1 enabled). Setting bit 16
        // here makes heating the rear glass start battery preconditioning as well.
        {"65 08 00 00 c0 c0 20 00 00 00", "обогрев заднего стекла вкл"},
        {"65 08 00 00 c0 c0 10 00 00 00", "обогрев заднего стекла выкл"},
        {"7a 08 00 00 00 00 01 00 00 00", "автодальний вкл"},
        {"7a 08 00 00 00 00 02 00 00 00", "автодальний выкл"},
        {"68 08 02 00 00 f0 2c 54 08 00", "форсе EV вкл"},
        {"68 08 02 00 00 f0 2c 24 08 00", "форсе EV выкл"},
    };
}
