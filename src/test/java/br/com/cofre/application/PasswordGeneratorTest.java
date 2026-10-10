package br.com.cofre.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordGeneratorTest {
    @Test
    void generatesTenCharactersIncludingEveryCharacterCategory() {
        var generator = new PasswordGenerator();
        for (int sample = 0; sample < 100; sample++) {
            String password = generator.generate();
            assertEquals(10, password.length());
            assertTrue(password.matches("[A-Za-z0-9!@#$%&*?+=_-]{10}"));
            assertTrue(password.chars().anyMatch(Character::isUpperCase));
            assertTrue(password.chars().anyMatch(Character::isLowerCase));
            assertTrue(password.chars().anyMatch(Character::isDigit));
            assertTrue(password.chars().anyMatch(character -> "!@#$%&*?+=_-".indexOf(character) >= 0));
        }
    }
}
