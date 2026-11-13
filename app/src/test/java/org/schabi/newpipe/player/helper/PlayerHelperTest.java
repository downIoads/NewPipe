package org.schabi.newpipe.player.helper;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PlayerHelperTest {
    @Test
    public void captionLanguagesPutEnglishVariantsFirstAndSortBothGroups() {
        final List<String> languages = new ArrayList<>(Arrays.asList(
                "Zulu",
                "English (United States)",
                "en-gb",
                "en-US (CC)",
                "Deutsch",
                "EN_us",
                "En (auto-generated)",
                "English (auto-generated)",
                "eng",
                "Arabic",
                "en",
                "Español",
                null));

        languages.sort(PlayerHelper.getCaptionLanguageComparator());

        assertEquals(Arrays.asList(
                "en",
                "En (auto-generated)",
                "en-gb",
                "en-US (CC)",
                "EN_us",
                "eng",
                "English (auto-generated)",
                "English (United States)",
                "Arabic",
                "Deutsch",
                "Español",
                "Zulu",
                null), languages);
    }

    @Test
    public void captionLanguagesRecognizeEnglishDisplayNameModifiers() {
        final List<String> languages = new ArrayList<>(Arrays.asList(
                "French",
                "British English",
                "English - forced",
                "American English",
                "Bengali"));

        languages.sort(PlayerHelper.getCaptionLanguageComparator());

        assertEquals(Arrays.asList(
                "American English",
                "British English",
                "English - forced",
                "Bengali",
                "French"), languages);
    }
}
