package ru.big.town.anative;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ClusterNavigationTextTest {
    @Test
    public void hidesStreetRegardlessOfTransliterationAndRestoresOriginalText() {
        assertEquals("", ClusterNavigationText.format("улица Мира", false, false));
        assertEquals("", ClusterNavigationText.format("улица Мира", true, false));
        assertEquals("ул. Мира", ClusterNavigationText.format("улица Мира", false, true));
        assertEquals("ul. Mira", ClusterNavigationText.format("улица Мира", true, true));
    }

    @Test
    public void abbreviatesAddressWordsWithoutChangingNamesOrExistingAbbreviations() {
        assertEquals(
                "ул. Ленина, пр-кт Мира, пр-д Южный",
                ClusterNavigationText.format("улица Ленина, проспект Мира, проезд Южный", false));
        assertEquals(
                "Бульварная ул., ул. Мира, ш. Энтузиастов",
                ClusterNavigationText.format(
                        "Бульварная улица, ул. Мира, шоссе Энтузиастов", false));
        assertEquals(
                "наб. Реки, пер. Тихий, пл. Победы, мкр. Северный",
                ClusterNavigationText.format(
                        "набережная Реки, переулок Тихий, площадь Победы, микрорайон Северный",
                        false));
    }

    @Test
    public void transliteratesAfterAbbreviationAndPreservesLatinDigitsAndPunctuation() {
        assertEquals(
                "ul. Yuzhnaya, pr-kt Mira / M-11",
                ClusterNavigationText.format("улица Южная, проспект Мира / M-11", true));
        assertEquals(
                "Yozh, Shchyolkovo, Tver, Podezd",
                ClusterNavigationText.format("Ёж, Щёлково, Тверь, Подъезд", true));
        assertEquals("", ClusterNavigationText.format(null, true));
        assertEquals("", ClusterNavigationText.format("", false));
    }
}
