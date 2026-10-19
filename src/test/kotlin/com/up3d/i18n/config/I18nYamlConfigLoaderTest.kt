package com.hegg5577.i18n.config

import org.junit.Test
import org.junit.Assert.*

class I18nYamlConfigLoaderTest {

    @Test
    fun testGetString() {
        val config = mapOf(
            "key1" to "value1",
            "key2" to "  ",
            "key3" to 123,
        )

        assertEquals("value1", I18nYamlConfigLoader.getString(config, "key1"))
        assertEquals("default", I18nYamlConfigLoader.getString(config, "key2", "default"))
        assertEquals("default", I18nYamlConfigLoader.getString(config, "nonexist", "default"))
        assertEquals("default", I18nYamlConfigLoader.getString(config, "key3", "default"))
    }

    @Test
    fun testGetBoolean() {
        val config = mapOf(
            "bool1" to true,
            "bool2" to false,
            "str1" to "true",
            "str2" to "yes",
            "str3" to "1",
            "str4" to "false",
            "num1" to 1,
        )

        assertTrue(I18nYamlConfigLoader.getBoolean(config, "bool1"))
        assertFalse(I18nYamlConfigLoader.getBoolean(config, "bool2"))
        assertTrue(I18nYamlConfigLoader.getBoolean(config, "str1"))
        assertTrue(I18nYamlConfigLoader.getBoolean(config, "str2"))
        assertTrue(I18nYamlConfigLoader.getBoolean(config, "str3"))
        assertFalse(I18nYamlConfigLoader.getBoolean(config, "str4"))
        assertFalse(I18nYamlConfigLoader.getBoolean(config, "num1", false))
        assertFalse(I18nYamlConfigLoader.getBoolean(config, "nonexist", false))
        assertTrue(I18nYamlConfigLoader.getBoolean(config, "nonexist", true))
    }

    @Test
    fun testGetStringList() {
        val config = mapOf(
            "list1" to listOf("a", "b", "c"),
            "list2" to listOf("x", 123, "z"),
            "list3" to "not a list",
            "list4" to emptyList<String>(),
        )

        assertEquals(listOf("a", "b", "c"), I18nYamlConfigLoader.getStringList(config, "list1"))
        assertEquals(listOf("x", "z"), I18nYamlConfigLoader.getStringList(config, "list2"))
        assertEquals(emptyList<String>(), I18nYamlConfigLoader.getStringList(config, "list3"))
        assertEquals(emptyList<String>(), I18nYamlConfigLoader.getStringList(config, "list4"))
        assertEquals(listOf("default"), I18nYamlConfigLoader.getStringList(config, "nonexist", listOf("default")))
    }
}
