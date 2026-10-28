package com.qqbot.emoji;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

class EmojiTimeFilterTest {

    @Test
    void hintIsNotEmpty() {
        assertFalse(EmojiTimeFilter.hint(LocalTime.of(9, 0)).isBlank());
    }

    @Test
    void morningAndNoonHintsDiffer() {
        String morning = EmojiTimeFilter.hint(LocalTime.of(8, 0));
        String noon = EmojiTimeFilter.hint(LocalTime.of(12, 0));
        assertNotEquals(morning, noon);
    }

    @Test
    void nightHintDiffersFromNoon() {
        String night = EmojiTimeFilter.hint(LocalTime.of(1, 0));
        String noon = EmojiTimeFilter.hint(LocalTime.of(12, 0));
        assertNotEquals(night, noon);
    }
}
